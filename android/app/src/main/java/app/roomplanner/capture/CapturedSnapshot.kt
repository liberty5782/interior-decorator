package app.roomplanner.capture

import app.roomplanner.api.SnapshotMeta

/**
 * A metric snapshot ready to upload: a JPEG, optional depth buffer, and the pose, intrinsics and
 * planes that make it measurable.
 *
 * This is the boundary between capture and everything else. Only the code that *produces* one
 * (ArCoreCapture today) knows about ARCore; uploading, viewing and placing work on this type.
 * A future non-ARCore capture path (e.g. for a de-Googled phone) produces the same type with
 * `capture_source = "manual"`.
 */
class CapturedSnapshot(
    val jpeg: ByteArray,
    val depth: ByteArray?,
    val meta: SnapshotMeta,
)
