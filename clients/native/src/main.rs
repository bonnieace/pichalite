use pichalite_native::{Request, collect, compress, validate};
use std::io::{self, Read, Write};
fn run() -> Result<bool, Box<dyn std::error::Error + Send + Sync>> {
    if std::env::args().any(|s| s == "--version") {
        println!("pichalite-cli 0.1.0");
        return Ok(true);
    }
    if std::env::args().any(|s| s == "--help") {
        println!(
            "PichaLite CLI: send JSON on stdin with inputs, output_dir, quality (40–95), max_edge (256–40000). Emits one JSON result per image. Originals are never modified."
        );
        return Ok(true);
    }
    let mut input = String::new();
    io::stdin()
        .take(1024 * 1024 + 1)
        .read_to_string(&mut input)?;
    if input.len() > 1024 * 1024 {
        return Err("Request exceeds 1 MB".into());
    }
    let request: Request = serde_json::from_str(&input)?;
    validate(&request)?;
    let files = collect(&request.inputs, Some(&request.output_dir))?;
    if files.is_empty() {
        return Err("No JPEG, PNG or WebP images found".into());
    }
    let mut success = true;
    let mut stdout = io::BufWriter::new(io::stdout().lock());
    for input in files {
        let result = compress(
            &input,
            &request.output_dir,
            request.quality,
            request.max_edge,
        );
        success &= result.status != "failed";
        serde_json::to_writer(&mut stdout, &result)?;
        writeln!(stdout)?;
        stdout.flush()?;
    }
    Ok(success)
}
fn main() {
    match run() {
        Ok(true) => {}
        Ok(false) => std::process::exit(2),
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1)
        }
    }
}
