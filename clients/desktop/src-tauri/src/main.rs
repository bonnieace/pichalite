#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use base64::Engine;
use pichalite_native::{Outcome, collect, compress};
use serde::Serialize;
use std::{
    collections::HashSet,
    io::Cursor,
    path::{Path, PathBuf},
    sync::Mutex,
};
use tauri::State;
#[derive(Default)]
struct Session {
    selected: Mutex<HashSet<PathBuf>>,
    output: Mutex<Option<PathBuf>>,
}
#[derive(Serialize)]
struct Photo {
    path: PathBuf,
    name: String,
    bytes: u64,
}
fn list(paths: Vec<PathBuf>, session: &Session) -> Result<Vec<Photo>, String> {
    let excluded = session.output.lock().map_err(|e| e.to_string())?.clone();
    let files = collect(&paths, excluded.as_deref())?;
    let mut selected = session.selected.lock().map_err(|e| e.to_string())?;
    if selected.len() + files.iter().filter(|p| !selected.contains(*p)).count()
        > pichalite_native::MAX_FILES
    {
        return Err("Choose at most 5,000 images per session".into());
    }
    files
        .into_iter()
        .map(|path| {
            let bytes = std::fs::metadata(&path).map_err(|e| e.to_string())?.len();
            selected.insert(path.clone());
            Ok(Photo {
                name: path
                    .file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .into_owned(),
                path,
                bytes,
            })
        })
        .collect()
}
#[tauri::command]
async fn choose_photos(folder: bool, session: State<'_, Session>) -> Result<Vec<Photo>, String> {
    let paths = tauri::async_runtime::spawn_blocking(move || {
        if folder {
            rfd::FileDialog::new()
                .set_title("Choose a photo folder")
                .pick_folder()
                .map(|p| vec![p])
        } else {
            rfd::FileDialog::new()
                .add_filter("Photos", &["jpg", "jpeg", "png", "webp"])
                .set_title("Choose photos")
                .pick_files()
        }
    })
    .await
    .map_err(|e| e.to_string())?;
    match paths {
        Some(paths) => list(paths, &session),
        None => Ok(Vec::new()),
    }
}
#[tauri::command]
fn import_paths(paths: Vec<PathBuf>, session: State<'_, Session>) -> Result<Vec<Photo>, String> {
    list(paths, &session)
}
#[tauri::command]
async fn choose_output(session: State<'_, Session>) -> Result<Option<PathBuf>, String> {
    let path = tauri::async_runtime::spawn_blocking(|| {
        rfd::FileDialog::new()
            .set_title("Save smaller copies to…")
            .pick_folder()
    })
    .await
    .map_err(|e| e.to_string())?;
    let path = path.map(|p| p.join("PichaLite"));
    if let Some(ref path) = path {
        std::fs::create_dir_all(path).map_err(|e| e.to_string())?;
        *session.output.lock().map_err(|e| e.to_string())? = Some(path.clone());
    }
    Ok(path)
}
fn allowed(path: &Path, session: &Session) -> Result<PathBuf, String> {
    let path = path.canonicalize().map_err(|e| e.to_string())?;
    if !session
        .selected
        .lock()
        .map_err(|e| e.to_string())?
        .contains(&path)
    {
        return Err("Select the photo first".into());
    }
    Ok(path)
}
#[tauri::command]
async fn compress_photo(
    input: PathBuf,
    quality: u8,
    max_edge: u32,
    session: State<'_, Session>,
) -> Result<Outcome, String> {
    let input = allowed(&input, &session)?;
    let output = session
        .output
        .lock()
        .map_err(|e| e.to_string())?
        .clone()
        .ok_or("Choose a destination folder first")?;
    tauri::async_runtime::spawn_blocking(move || compress(&input, &output, quality, max_edge))
        .await
        .map_err(|e| e.to_string())
}
#[tauri::command]
async fn preview(input: PathBuf, session: State<'_, Session>) -> Result<String, String> {
    let input = allowed(&input, &session)?;
    tauri::async_runtime::spawn_blocking(move || {
        if std::fs::metadata(&input).map_err(|e| e.to_string())?.len() > 40 * 1024 * 1024 {
            return Err("Photo exceeds 40 MB".into());
        }
        let reader = image::ImageReader::open(input)
            .map_err(|e| e.to_string())?
            .with_guessed_format()
            .map_err(|e| e.to_string())?;
        let mut decoder = reader.into_decoder().map_err(|e| e.to_string())?;
        use image::ImageDecoder;
        if decoder.total_bytes() > 160 * 1024 * 1024 {
            return Err("Image too large to preview".into());
        }
        let orientation = decoder.orientation().map_err(|e| e.to_string())?;
        let mut img = image::DynamicImage::from_decoder(decoder).map_err(|e| e.to_string())?;
        img.apply_orientation(orientation);
        let mut bytes = Cursor::new(Vec::new());
        img.thumbnail(240, 240)
            .write_to(&mut bytes, image::ImageFormat::Png)
            .map_err(|e| e.to_string())?;
        Ok(format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes.into_inner())
        ))
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
fn clear_session(session: State<'_, Session>) -> Result<(), String> {
    session.selected.lock().map_err(|e| e.to_string())?.clear();
    Ok(())
}
#[tauri::command]
fn reveal_output(session: State<'_, Session>) -> Result<(), String> {
    let path = session
        .output
        .lock()
        .map_err(|e| e.to_string())?
        .clone()
        .ok_or("Choose an output folder first")?;
    tauri_plugin_opener::reveal_item_in_dir(path).map_err(|e| e.to_string())
}
fn main() {
    tauri::Builder::default()
        .manage(Session::default())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            choose_photos,
            import_paths,
            choose_output,
            compress_photo,
            preview,
            clear_session,
            reveal_output
        ])
        .run(tauri::generate_context!())
        .expect("Could not start PichaLite");
}
