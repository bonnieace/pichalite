use pichalite::{Engine, PhotoJob, optimise};
use serde_json::json;

#[tokio::test]
async fn enqueue_is_atomic_deduplicated_and_survives_reopening() {
    let temp = tempfile::tempdir().unwrap();
    let engine = Engine::open(temp.path()).await.unwrap();
    let input = temp.path().join("inbox/test.png");
    image::RgbImage::new(20, 20).save(&input).unwrap();
    let id = engine.enqueue(&input, 1600, 82).await.unwrap();
    assert_eq!(engine.enqueue(&input, 1600, 82).await.unwrap(), id);
    engine.set_paused(true).await.unwrap();
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM Jobs")
        .fetch_one(&engine.pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
    engine.pool.close().await;
    drop(engine);
    let reopened = Engine::open(temp.path()).await.unwrap();
    let snapshot = reopened
        .dispatch(json!({"action":"snapshot"}))
        .await
        .unwrap();
    assert_eq!(snapshot["paused"], true);
    assert_eq!(snapshot["pending"], 1);
    assert_eq!(snapshot["jobs"][0]["id"], id);
}

#[tokio::test]
async fn root_lock_and_import_boundary_prevent_conflicting_writers() {
    let temp = tempfile::tempdir().unwrap();
    let engine = Engine::open(temp.path()).await.unwrap();
    assert!(Engine::open(temp.path()).await.is_err());
    let outside = temp.path().join("outside.png");
    image::RgbImage::new(4, 4).save(&outside).unwrap();
    assert!(engine.enqueue(&outside, 1600, 82).await.is_err());
}

#[test]
fn image_job_resizes_flattens_alpha_and_reuses_one_output() {
    let temp = tempfile::tempdir().unwrap();
    let input = temp.path().join("transparent.png");
    image::RgbaImage::from_pixel(2400, 1200, image::Rgba([0, 0, 0, 0]))
        .save(&input)
        .unwrap();
    let job = PhotoJob {
        id: "same-job".into(),
        input: input.to_str().unwrap().into(),
        name: "transparent.png".into(),
        max_edge: 1600,
        quality: 82,
    };
    let first = optimise(&job, temp.path()).unwrap();
    let second = optimise(&job, temp.path()).unwrap();
    assert_eq!((first.width, first.height), (1600, 800));
    assert_eq!(first.output, second.output);
    let output = image::open(first.output).unwrap().to_rgb8();
    assert!(output.get_pixel(100, 100)[0] > 245);
    assert!(!temp.path().join("same-job.jpg.part").exists());
}
