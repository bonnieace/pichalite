//! Shared desktop/extension adapter around PichaLite's existing Rust encoder.
//! This adapter is a bounded batch runner, not the Android Rivium/Apalis service.
use pichalite::{PhotoJob, optimise};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
};

pub const MAX_FILES: usize = 5000;
const MAX_BYTES: u64 = 40 * 1024 * 1024;
fn quality() -> u8 {
    82
}
fn edge() -> u32 {
    4096
}
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub inputs: Vec<PathBuf>,
    pub output_dir: PathBuf,
    #[serde(default = "quality")]
    pub quality: u8,
    #[serde(default = "edge")]
    pub max_edge: u32,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Outcome {
    pub input: PathBuf,
    pub output: Option<PathBuf>,
    pub input_bytes: u64,
    pub output_bytes: u64,
    pub saved_bytes: u64,
    pub status: String,
    pub error: Option<String>,
}
impl Outcome {
    fn failed(path: &Path, error: String) -> Self {
        Self {
            input: path.into(),
            output: None,
            input_bytes: 0,
            output_bytes: 0,
            saved_bytes: 0,
            status: "failed".into(),
            error: Some(error),
        }
    }
}
pub fn validate(request: &Request) -> Result<(), String> {
    if !(40..=95).contains(&request.quality) {
        return Err("Quality must be between 40 and 95".into());
    }
    if !(256..=40000).contains(&request.max_edge) {
        return Err("Maximum edge must be 256–40000 pixels".into());
    }
    if request.inputs.is_empty() || request.inputs.len() > MAX_FILES {
        return Err("Choose between 1 and 5,000 files or folders".into());
    }
    Ok(())
}
pub fn supported(path: &Path) -> bool {
    path.extension()
        .and_then(|p| p.to_str())
        .is_some_and(|p| ["jpg", "jpeg", "png", "webp"].contains(&p.to_ascii_lowercase().as_str()))
}
pub fn collect(inputs: &[PathBuf], exclude: Option<&Path>) -> Result<Vec<PathBuf>, String> {
    let exclude = exclude.and_then(|p| p.canonicalize().ok());
    let mut stack = inputs.to_vec();
    let mut files = Vec::new();
    let mut visited = HashSet::new();
    let mut examined = 0;
    while let Some(path) = stack.pop() {
        examined += 1;
        if examined > 100000 {
            return Err("Folder contains too many entries. Choose a smaller folder.".into());
        }
        let meta = fs::symlink_metadata(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        if meta.file_type().is_symlink() {
            continue;
        }
        let canonical = path.canonicalize().map_err(|e| e.to_string())?;
        if exclude.as_ref().is_some_and(|e| canonical.starts_with(e)) {
            continue;
        }
        if !visited.insert(canonical.clone()) {
            continue;
        }
        if meta.is_dir() {
            for entry in fs::read_dir(&canonical).map_err(|e| e.to_string())? {
                stack.push(entry.map_err(|e| e.to_string())?.path());
            }
        } else if meta.is_file() && supported(&canonical) {
            files.push(canonical);
            if files.len() > MAX_FILES {
                return Err("Choose at most 5,000 images per batch".into());
            }
        }
    }
    files.sort();
    Ok(files)
}
pub fn compress(input: &Path, output_dir: &Path, quality: u8, max_edge: u32) -> Outcome {
    match compress_inner(input, output_dir, quality, max_edge) {
        Ok(result) => result,
        Err(error) => Outcome::failed(input, error.to_string()),
    }
}
fn compress_inner(
    input: &Path,
    output_dir: &Path,
    quality: u8,
    max_edge: u32,
) -> Result<Outcome, Box<dyn std::error::Error + Send + Sync>> {
    if !(40..=95).contains(&quality) || !(256..=40000).contains(&max_edge) {
        return Err("Invalid compression settings".into());
    }
    if !supported(input) || !fs::symlink_metadata(input)?.is_file() {
        return Err("Choose a regular JPEG, PNG or WebP file".into());
    }
    fs::create_dir_all(output_dir)?;
    // Snapshot once; never write to the source. Bound reads even if the file grows.
    let mut bytes = Vec::new();
    File::open(input)?
        .take(MAX_BYTES + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_BYTES {
        return Err("Photo exceeds 40 MB".into());
    }
    let stage = tempfile::Builder::new()
        .prefix(".pichalite-")
        .tempdir_in(output_dir)?;
    let snapshot = stage.path().join("source");
    fs::write(&snapshot, &bytes)?;
    let job = PhotoJob {
        id: "encoded".into(),
        input: snapshot.to_string_lossy().into_owned(),
        name: "source".into(),
        quality,
        max_edge,
    };
    let result = optimise(&job, stage.path())?;
    let retained = result.bytes >= bytes.len() as u64;
    let stem = input
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("photo");
    let ext = if retained {
        input.extension().and_then(|s| s.to_str()).unwrap_or("jpg")
    } else {
        "jpg"
    };
    let label = if retained { "original" } else { "pichalite" };
    let mut ready = tempfile::NamedTempFile::new_in(output_dir)?;
    if retained {
        ready.write_all(&bytes)?;
    } else {
        std::io::copy(&mut File::open(&result.output)?, ready.as_file_mut())?;
    }
    ready.as_file().sync_all()?;
    let output_bytes = ready.as_file().metadata()?.len();
    let mut selected = None;
    for suffix in 0..10000 {
        let name = if suffix == 0 {
            format!("{stem}-{label}.{ext}")
        } else {
            format!("{stem}-{label}-{suffix}.{ext}")
        };
        let path = output_dir.join(name);
        match ready.persist_noclobber(&path) {
            Ok(_) => {
                selected = Some(path);
                break;
            }
            Err(e) if e.error.kind() == std::io::ErrorKind::AlreadyExists => {
                ready = e.file;
            }
            Err(e) => return Err(e.error.into()),
        }
    }
    let output = selected.ok_or("Too many output filename collisions")?;
    Ok(Outcome {
        input: input.into(),
        output: Some(output),
        input_bytes: bytes.len() as u64,
        output_bytes,
        saved_bytes: (bytes.len() as u64).saturating_sub(output_bytes),
        status: if retained {
            "retained".into()
        } else {
            "compressed".into()
        },
        error: None,
    })
}
