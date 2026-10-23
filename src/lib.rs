//! One offline photo queue, shared by the process and Android JNI hosts.
use apalis::prelude::*;
use apalis_sqlite::{Config as QueueConfig, SqliteStorage};
use fs2::FileExt;
use image::{DynamicImage, ImageDecoder, ImageReader, codecs::jpeg::JpegEncoder};
use rivium::{Kind, Service, service};
use rivium_host::{App, Context};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{
    Row, SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
};
use std::{
    fs::{self, File, OpenOptions},
    io::BufWriter,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

pub type AnyError = Box<dyn std::error::Error + Send + Sync>;
const QUEUE: &str = "pichalite-photo-v1";
const MAX_INPUT: u64 = 40 * 1024 * 1024;
static ACTIVE: OnceLock<Mutex<Option<Arc<Engine>>>> = OnceLock::new();

#[derive(Default, Serialize, Deserialize)]
pub struct Config {}
pub struct PichaApp;

impl App for PichaApp {
    const NAME: &'static str = "pichalite";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");
    type Config = Config;

    fn services(_: &Config, host: &Context<Config>) -> rivium::Result<Vec<Box<dyn Service>>> {
        let root = host.paths().root().to_owned();
        Ok(vec![service(
            "photo-queue",
            Kind::Frontline,
            move |ctx| async move {
                let engine = Arc::new(Engine::open(&root).await.map_err(|error| {
                    eprintln!("Queue startup failed: {error}");
                    rivium_error(error)
                })?);
                *active().lock().unwrap() = Some(engine.clone());
                #[cfg(feature = "jni")]
                {
                    *JNI_HANDLE.get_or_init(|| Mutex::new(None)).lock().unwrap() =
                        Some(tokio::runtime::Handle::current());
                }
                let result = async {
                engine.scan().await.map_err(rivium_error)?;
                let backend = SqliteStorage::<PhotoJob>::new(&engine.pool)
                    .with_config(QueueConfig::default().queue(QUEUE).batch_size(1))
                    .poll_with_interval(Duration::from_millis(300));
                let control = WorkerContext::new("pichalite-local");
                *engine.worker.lock().unwrap() = Some(control.clone());
                let worker = WorkerBuilder::new(control.clone())
                    .backend(backend)
                    .concurrency(1)
                    .data(engine.clone())
                    .on_event({
                        let weak = Arc::downgrade(&engine);
                        move |worker, event| {
                            if matches!(event, Event::Start) && weak.upgrade().is_some_and(|e| e.paused.load(Ordering::Acquire)) {
                                let _ = worker.pause();
                            }
                        }
                    })
                    .build(process_photo);
                let run = worker.run();
                tokio::pin!(run);
                let scan = async {
                    loop {
                        tokio::time::sleep(Duration::from_secs(1)).await;
                        engine.scan().await.map_err(rivium_error)?;
                    }
                    #[allow(unreachable_code)]
                    Ok::<(), rivium::Error>(())
                };
                tokio::pin!(scan);
                ctx.ready();
                println!("PichaLite ready: {}", root.display());
                tokio::select! {
                        _ = ctx.stopped() => {
                            engine.stopping.store(true, Ordering::Release);
                            if control.stop().is_ok() {
                                tokio::time::timeout(Duration::from_secs(8), &mut run).await
                                    .map_err(|_| rivium::Error::internal("worker shutdown timed out"))?
                                    .map_err(|e| rivium::Error::internal(e.to_string()))?;
                            }
                        },
                        outcome = &mut run => {
                            outcome.map_err(|e| rivium::Error::internal(e.to_string()))?;
                            return Err(rivium::Error::internal("photo worker stopped unexpectedly"));
                        },
                        result = &mut scan => return result,
                }
                Ok(())
            }.await;
                *active().lock().unwrap() = None;
                // Release SQLite connections before a new JNI runtime generation
                // can reacquire the queue and reopen its WAL database.
                engine.pool.close().await;
                #[cfg(feature = "jni")]
                {
                    *JNI_HANDLE.get().unwrap().lock().unwrap() = None;
                }
                // Dropping the worker releases unfinished jobs. Startup recovery is safe
                // because the exclusive root lock prevents simultaneous app generations.
                result
            },
        )])
    }
}

fn active() -> &'static Mutex<Option<Arc<Engine>>> {
    ACTIVE.get_or_init(|| Mutex::new(None))
}
fn rivium_error(e: AnyError) -> rivium::Error {
    rivium::Error::internal(e.to_string())
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PhotoJob {
    pub id: String,
    pub input: String,
    pub name: String,
    pub max_edge: u32,
    pub quality: u8,
}

pub struct Engine {
    pub pool: SqlitePool,
    root: PathBuf,
    paused: AtomicBool,
    stopping: AtomicBool,
    worker: Mutex<Option<WorkerContext>>,
    _lock: File,
}

impl Engine {
    pub async fn open(root: &Path) -> Result<Self, AnyError> {
        fs::create_dir_all(root.join("inbox"))?;
        fs::create_dir_all(root.join("output"))?;
        let root = root.canonicalize()?;
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(root.join("queue.lock"))?;
        lock.try_lock_exclusive()
            .map_err(|_| "This queue is already open in another process")?;
        let options = SqliteConnectOptions::new()
            .filename(root.join("queue.sqlite"))
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Full)
            .busy_timeout(Duration::from_secs(5));
        // One local worker shares the database with JNI calls and the scanner.
        // Serialize their transactions, including Apalis registration/heartbeat,
        // rather than racing independent SQLite writers on Android.
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect_with(options)
            .await?;
        // Run the bundled migrations directly: setup() changes synchronous to OFF.
        SqliteStorage::migrations().run(&pool).await?;
        sqlx::query("CREATE TABLE IF NOT EXISTS photos (id TEXT PRIMARY KEY, input TEXT NOT NULL UNIQUE, name TEXT NOT NULL, input_bytes INTEGER NOT NULL, output TEXT, output_bytes INTEGER, width INTEGER, height INTEGER, error TEXT, created_at INTEGER NOT NULL DEFAULT (unixepoch()))").execute(&pool).await?;
        sqlx::query(
            "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )
        .execute(&pool)
        .await?;
        // Only this app owns the database. Recover leased jobs immediately, rather
        // than waiting for a heartbeat timeout after Android kills the process.
        sqlx::query("UPDATE Jobs SET status='Pending', lock_by=NULL, lock_at=NULL WHERE job_type=? AND status IN ('Running','Queued')").bind(QUEUE).execute(&pool).await?;
        sqlx::query("UPDATE Jobs SET lock_by=NULL,lock_at=NULL WHERE job_type=?")
            .bind(QUEUE)
            .execute(&pool)
            .await?;
        sqlx::query("DELETE FROM Workers WHERE worker_type=?")
            .bind(QUEUE)
            .execute(&pool)
            .await?;
        let paused =
            sqlx::query_scalar::<_, String>("SELECT value FROM settings WHERE key='paused'")
                .fetch_optional(&pool)
                .await?
                .is_some_and(|s| s == "true");
        Ok(Self {
            pool,
            root,
            paused: AtomicBool::new(paused),
            stopping: AtomicBool::new(false),
            worker: Mutex::new(None),
            _lock: lock,
        })
    }

    pub async fn enqueue(
        &self,
        input: &Path,
        max_edge: u32,
        quality: u8,
    ) -> Result<String, AnyError> {
        if !(512..=2560).contains(&max_edge) || !(40..=95).contains(&quality) {
            return Err("Unsupported photo settings".into());
        }
        let input = input.canonicalize()?;
        if input.parent() != Some(self.root.join("inbox").as_path()) {
            return Err("Import must be inside the app inbox".into());
        }
        let bytes = fs::metadata(&input)?.len();
        if bytes == 0 || bytes > MAX_INPUT {
            return Err("Choose a photo between 1 byte and 40 MB".into());
        }
        let input = input.to_str().ok_or("Unsupported filename")?.to_owned();
        if let Some(id) = sqlx::query_scalar::<_, String>("SELECT id FROM photos WHERE input=?")
            .bind(&input)
            .fetch_optional(&self.pool)
            .await?
        {
            return Ok(id);
        }
        let name = Path::new(&input)
            .file_name()
            .unwrap()
            .to_string_lossy()
            .split_once("__")
            .map(|(_, n)| n.to_owned())
            .unwrap_or_else(|| {
                Path::new(&input)
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .into_owned()
            });
        let id = uuid::Uuid::new_v4().to_string();
        let job = PhotoJob {
            id: id.clone(),
            input: input.clone(),
            name: name.clone(),
            max_edge,
            quality,
        };
        let task = TaskBuilder::new(serde_json::to_vec(&job)?)
            .idempotency_key(&id)
            .max_attempts(3)
            .build();
        let mut tx = self.pool.begin().await?;
        let added =
            sqlx::query("INSERT OR IGNORE INTO photos(id,input,name,input_bytes) VALUES(?,?,?,?)")
                .bind(&id)
                .bind(&input)
                .bind(name)
                .bind(bytes as i64)
                .execute(&mut *tx)
                .await?
                .rows_affected();
        if added == 0 {
            let existing: String = sqlx::query_scalar("SELECT id FROM photos WHERE input=?")
                .bind(&input)
                .fetch_one(&mut *tx)
                .await?;
            tx.commit().await?;
            return Ok(existing);
        }
        // The visible row and actual Apalis job are one durable transaction.
        apalis_sqlite::queries::push_tasks(&mut *tx, QUEUE, &[task]).await?;
        tx.commit().await?;
        Ok(id)
    }

    pub async fn scan(&self) -> Result<(), AnyError> {
        for file in fs::read_dir(self.root.join("inbox"))? {
            let path = file?.path();
            let ext = path
                .extension()
                .and_then(|s| s.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            if path.is_file() && ["jpg", "jpeg", "png", "webp"].contains(&ext.as_str()) {
                // A rejected/oversized file must not take down the worker service.
                if let Err(e) = self.enqueue(&path, 1600, 82).await {
                    eprintln!("Import {}: {e}", path.display());
                }
            }
        }
        Ok(())
    }

    pub async fn snapshot(&self) -> Result<Value, AnyError> {
        let rows = sqlx::query("SELECT p.*, j.status AS queue_status, j.attempts, j.max_attempts FROM photos p LEFT JOIN Jobs j ON j.job_type=? AND j.idempotency_key=p.id ORDER BY p.created_at DESC,p.rowid DESC LIMIT 500").bind(QUEUE).fetch_all(&self.pool).await?;
        let mut jobs = Vec::new();
        let mut pending = 0;
        let mut saved = 0;
        let paused = self.paused.load(Ordering::Acquire);
        for row in rows {
            let output: Option<String> = row.try_get("output")?;
            let qs: Option<String> = row.try_get("queue_status")?;
            let attempts: i64 = row.try_get::<Option<i64>, _>("attempts")?.unwrap_or(0);
            let max: i64 = row.try_get::<Option<i64>, _>("max_attempts")?.unwrap_or(3);
            let ready = output.as_ref().is_some_and(|s| Path::new(s).is_file());
            let failed = qs
                .as_deref()
                .is_some_and(|s| s == "Killed" || s == "Failed" && attempts >= max);
            let status = if ready {
                "ready"
            } else if failed {
                "failed"
            } else if !paused && qs.as_deref() == Some("Running") {
                "processing"
            } else {
                "queued"
            };
            if !ready && !failed {
                pending += 1;
            }
            let input_bytes: i64 = row.try_get("input_bytes")?;
            let output_bytes: Option<i64> = row.try_get("output_bytes")?;
            if ready {
                saved += (input_bytes - output_bytes.unwrap_or(input_bytes)).max(0);
            }
            jobs.push(json!({"id":row.try_get::<String,_>("id")?, "name":row.try_get::<String,_>("name")?, "input":row.try_get::<String,_>("input")?, "input_bytes":input_bytes, "output":output, "output_bytes":output_bytes, "width":row.try_get::<Option<i64>,_>("width")?, "height":row.try_get::<Option<i64>,_>("height")?, "status":status, "attempts":attempts, "error":row.try_get::<Option<String>,_>("error")?}));
        }
        Ok(json!({"ok":true,"paused":paused,"pending":pending,"saved_bytes":saved,"jobs":jobs}))
    }

    pub async fn set_paused(&self, paused: bool) -> Result<(), AnyError> {
        sqlx::query("INSERT INTO settings(key,value) VALUES('paused',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").bind(paused.to_string()).execute(&self.pool).await?;
        self.paused.store(paused, Ordering::Release);
        let worker = self.worker.lock().unwrap().clone();
        if let Some(worker) = worker {
            if paused && worker.is_running() {
                worker.pause()?;
            } else if !paused && worker.is_paused() {
                worker.resume()?;
            }
        }
        Ok(())
    }

    pub async fn dispatch(&self, request: Value) -> Result<Value, AnyError> {
        match request["action"].as_str().unwrap_or("") {
            "snapshot" => self.snapshot().await,
            "enqueue" => {
                let id = self
                    .enqueue(
                        Path::new(request["input"].as_str().ok_or("Missing input")?),
                        1600,
                        82,
                    )
                    .await?;
                Ok(json!({"ok":true,"id":id}))
            }
            "pause" => {
                self.set_paused(request["paused"].as_bool().ok_or("Missing paused")?)
                    .await?;
                self.snapshot().await
            }
            "retry" => {
                let id = request["id"].as_str().ok_or("Missing job ID")?;
                sqlx::query("UPDATE Jobs SET status='Pending',attempts=0,lock_by=NULL,lock_at=NULL,done_at=NULL WHERE job_type=? AND idempotency_key=? AND status IN ('Failed','Killed') AND attempts>=max_attempts").bind(QUEUE).bind(id).execute(&self.pool).await?;
                self.snapshot().await
            }
            _ => Err("Unknown action".into()),
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct PhotoResult {
    pub output: String,
    pub bytes: u64,
    pub width: u32,
    pub height: u32,
}

pub fn optimise(job: &PhotoJob, output_dir: &Path) -> Result<PhotoResult, AnyError> {
    let input_size = fs::metadata(&job.input)?.len();
    if input_size > MAX_INPUT {
        return Err("Photo exceeds 40 MB".into());
    }
    let reader = ImageReader::open(&job.input)?.with_guessed_format()?;
    let mut decoder = reader.into_decoder()?;
    if decoder.total_bytes() > 160 * 1024 * 1024 {
        return Err("Photo exceeds the 40 megapixel processing limit".into());
    }
    let orientation = decoder.orientation()?;
    let mut img = DynamicImage::from_decoder(decoder)?;
    img.apply_orientation(orientation);
    let img = if img.width().max(img.height()) > job.max_edge {
        img.resize(
            job.max_edge,
            job.max_edge,
            image::imageops::FilterType::Lanczos3,
        )
    } else {
        img
    };
    // JPEG has no alpha channel. Flatten transparent pixels onto white.
    let mut rgba = img.to_rgba8();
    for p in rgba.pixels_mut() {
        let alpha = p[3] as u16;
        for c in 0..3 {
            p[c] = ((p[c] as u16 * alpha + 255 * (255 - alpha) + 127) / 255) as u8;
        }
        p[3] = 255;
    }
    let rgb = DynamicImage::ImageRgba8(rgba).to_rgb8();
    let output = output_dir.join(format!("{}.jpg", job.id));
    let tmp = output.with_extension("jpg.part");
    let mut writer = BufWriter::new(File::create(&tmp)?);
    JpegEncoder::new_with_quality(&mut writer, job.quality)
        .encode_image(&DynamicImage::ImageRgb8(rgb))?;
    use std::io::Write;
    writer.flush()?;
    writer.get_ref().sync_all()?;
    drop(writer);
    fs::rename(&tmp, &output)?;
    #[cfg(unix)]
    File::open(output_dir)?.sync_all()?;
    Ok(PhotoResult {
        output: output.to_str().ok_or("Unsupported output path")?.to_owned(),
        bytes: fs::metadata(&output)?.len(),
        width: img.width(),
        height: img.height(),
    })
}

async fn process_photo(job: PhotoJob, data: Data<Arc<Engine>>) -> Result<PhotoResult, AnyError> {
    let engine = data.0;
    while engine.paused.load(Ordering::Acquire) {
        if engine.stopping.load(Ordering::Acquire) {
            return Err("Interrupted while paused; resume on next launch".into());
        }
        tokio::time::sleep(Duration::from_millis(150)).await;
    }
    // A completed output and its DB record make repeated execution harmless.
    let row = sqlx::query("SELECT output,output_bytes,width,height FROM photos WHERE id=?")
        .bind(&job.id)
        .fetch_one(&engine.pool)
        .await?;
    if let Some(output) = row.try_get::<Option<String>, _>("output")?
        && Path::new(&output).is_file()
    {
        return Ok(PhotoResult {
            output,
            bytes: row.try_get::<i64, _>("output_bytes")? as u64,
            width: row.try_get::<i64, _>("width")? as u32,
            height: row.try_get::<i64, _>("height")? as u32,
        });
    }
    let root = engine.root.clone();
    let copy = job.clone();
    // Keep the exclusive queue lock alive until CPU work exits, even on shutdown.
    let keepalive = engine.clone();
    let result = tokio::task::spawn_blocking(move || {
        let _keepalive = keepalive;
        optimise(&copy, &root.join("output"))
    })
    .await?;
    match result {
        Ok(result) => {
            sqlx::query(
                "UPDATE photos SET output=?,output_bytes=?,width=?,height=?,error=NULL WHERE id=?",
            )
            .bind(&result.output)
            .bind(result.bytes as i64)
            .bind(result.width as i64)
            .bind(result.height as i64)
            .bind(&job.id)
            .execute(&engine.pool)
            .await?;
            println!("Ready: {} -> {} bytes", job.name, result.bytes);
            Ok(result)
        }
        Err(e) => {
            sqlx::query("UPDATE photos SET error=? WHERE id=?")
                .bind(e.to_string())
                .bind(&job.id)
                .execute(&engine.pool)
                .await?;
            Err(e)
        }
    }
}

#[cfg(feature = "jni")]
mod android {
    use super::*;
    use jni::{
        JNIEnv,
        objects::{JClass, JString},
        sys::jstring,
    };

    rivium_jni::export!(class = "ke/co/pichalite/PichaRuntime", app = PichaApp);

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_ke_co_pichalite_NativeJobs_nativeCall(
        mut env: JNIEnv,
        _: JClass,
        request: JString,
    ) -> jstring {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
            || -> Result<Value, AnyError> {
                let request: String = env.get_string(&request)?.into();
                let request: Value = serde_json::from_str(&request)?;
                let engine = active()
                    .lock()
                    .map_err(|_| "Runtime unavailable")?
                    .clone()
                    .ok_or("Runtime is starting; try again")?;
                // Called only from Android's IO dispatcher or a desktop JVM test.
                let handle = JNI_HANDLE
                    .get()
                    .ok_or("Runtime not ready")?
                    .lock()
                    .map_err(|_| "Runtime unavailable")?
                    .clone()
                    .ok_or("Runtime stopped")?;
                handle.block_on(engine.dispatch(request))
            },
        ));
        let reply = match result {
            Ok(Ok(v)) => v,
            Ok(Err(e)) => json!({"ok":false,"error":e.to_string()}),
            Err(_) => json!({"ok":false,"error":"Native operation failed"}),
        };
        env.new_string(reply.to_string())
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}

#[cfg(feature = "jni")]
static JNI_HANDLE: OnceLock<Mutex<Option<tokio::runtime::Handle>>> = OnceLock::new();
