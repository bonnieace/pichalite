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


#[tokio::test]
async fn totals_cover_more_than_500_results_and_survive_cache_changes_and_restart() {
    let temp = tempfile::tempdir().unwrap();
    let engine = Engine::open(temp.path()).await.unwrap();
    engine.set_paused(true).await.unwrap();
    let mut transaction = engine.pool.begin().await.unwrap();
    for index in 0..620 {
        sqlx::query("INSERT INTO photos(id,input,name,input_bytes,output,output_bytes,width,height) VALUES(?,?,?,?,?,?,1,1)")
            .bind(format!("finished-{index}"))
            .bind(temp.path().join(format!("inbox/{index}.png")).to_str().unwrap())
            .bind(format!("{index}.png"))
            .bind(100_i64)
            .bind(temp.path().join(format!("output/{index}.jpg")).to_str().unwrap())
            .bind(40_i64)
            .execute(&mut *transaction)
            .await
            .unwrap();
    }
    transaction.commit().await.unwrap();
    let before = engine.snapshot().await.unwrap();
    assert_eq!(before["jobs"].as_array().unwrap().len(), 620);
    assert_eq!(before["completed"], 620);
    assert_eq!(before["saved_bytes"], 620 * 60);
    // Adding new work cannot evict earlier completed results from the total.
    let input = temp.path().join("inbox/new.png");
    std::fs::write(&input, [1_u8; 100]).unwrap();
    engine.enqueue(&input, 1600, 82).await.unwrap();
    let during = engine.snapshot().await.unwrap();
    assert_eq!(during["total"], 621);
    assert_eq!(during["saved_bytes"], before["saved_bytes"]);
    engine.pool.close().await;
    drop(engine);
    let reopened = Engine::open(temp.path()).await.unwrap();
    let after = reopened.snapshot().await.unwrap();
    assert_eq!(after["saved_bytes"], before["saved_bytes"]);
    assert_eq!(after["completed"], 620);
}

#[tokio::test]
async fn verified_local_export_releases_caches_and_counts_reclaimed_bytes_once() {
    let temp = tempfile::tempdir().unwrap();
    let engine = Engine::open(temp.path()).await.unwrap();
    let input = temp.path().join("inbox/local.png");
    let output = temp.path().join("output/local.jpg");
    std::fs::write(&input, [1_u8; 120]).unwrap();
    std::fs::write(&output, [2_u8; 40]).unwrap();
    let id = engine.enqueue(&input, 1600, 82).await.unwrap();
    assert!(engine.finish_local_export(&id, "file:///wrong").await.is_err());
    assert!(input.exists() && output.exists());
    sqlx::query("UPDATE photos SET output=?,output_bytes=40,width=1,height=1 WHERE id=?")
        .bind(output.to_str().unwrap())
        .bind(&id)
        .execute(&engine.pool)
        .await
        .unwrap();
    let destination = "content://media/external/images/media/42";
    engine.finish_local_export(&id, destination).await.unwrap();
    assert!(!input.exists() && !output.exists());
    let snapshot = engine.snapshot().await.unwrap();
    assert_eq!(snapshot["saved_bytes"], 80);
    assert_eq!(snapshot["reclaimed_bytes"], 80);
    assert_eq!(snapshot["pending"], 0);
    assert_eq!(snapshot["jobs"][0]["status"], "ready");
    assert_eq!(snapshot["jobs"][0]["saved_uri"], destination);
    engine.finish_local_export(&id, destination).await.unwrap();
    assert_eq!(engine.snapshot().await.unwrap()["reclaimed_bytes"], 80);
    engine.pool.close().await;
    drop(engine);
    let reopened = Engine::open(temp.path()).await.unwrap();
    assert_eq!(reopened.snapshot().await.unwrap()["reclaimed_bytes"], 80);
}
