use pichalite_native::{Request, collect, compress, validate};
use std::fs;
#[test]
fn saves_verified_copies_without_overwriting_or_changing_sources() {
    let root = tempfile::tempdir().unwrap();
    let input = root.path().join("photo.png");
    let out = root.path().join("out");
    image::RgbImage::from_fn(800, 600, |x, y| {
        image::Rgb([(x % 256) as u8, (y % 256) as u8, ((x * y) % 256) as u8])
    })
    .save(&input)
    .unwrap();
    let before = fs::read(&input).unwrap();
    let a = compress(&input, &out, 80, 512);
    let b = compress(&input, &out, 80, 512);
    assert_ne!(a.status, "failed", "{:?}", a.error);
    assert!(a.saved_bytes > 0);
    assert_ne!(a.output, b.output);
    assert_eq!(before, fs::read(&input).unwrap());
    let decoded = image::open(a.output.unwrap()).unwrap();
    assert_eq!((decoded.width(), decoded.height()), (512, 384));
    let files = collect(&[root.path().into(), input.clone()], Some(&out)).unwrap();
    assert_eq!(files, vec![input.canonicalize().unwrap()]);
    assert_eq!(fs::read_dir(&out).unwrap().count(), 2);
}
#[test]
fn tiny_inputs_keep_original_bytes_and_corrupt_files_do_not_create_outputs() {
    let root = tempfile::tempdir().unwrap();
    let input = root.path().join("tiny.png");
    let out = root.path().join("out");
    image::RgbImage::new(1, 1).save(&input).unwrap();
    let a = compress(&input, &out, 82, 4096);
    assert_eq!(a.status, "retained");
    assert_eq!(a.saved_bytes, 0);
    assert_eq!(
        fs::read(&input).unwrap(),
        fs::read(a.output.unwrap()).unwrap()
    );
    let broken = root.path().join("broken.jpg");
    fs::write(&broken, b"not an image").unwrap();
    assert_eq!(compress(&broken, &out, 82, 4096).status, "failed");
    assert_eq!(fs::read_dir(&out).unwrap().count(), 1);
    let req = Request {
        inputs: vec![input],
        output_dir: out,
        quality: 0,
        max_edge: 4096,
    };
    assert!(validate(&req).is_err());
}
