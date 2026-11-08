package io.github.mikevillasis.espeonanim.shared.packaging

import io.github.mikevillasis.espeonanim.shared.manifest.Manifest

/** One file to store in the package: [zipPath] inside the archive, [localPath] on the device. */
data class PackageFile(val zipPath: String, val localPath: String)

/** Manifest plus the files it refers to. */
data class PackagePlan(val manifest: Manifest, val files: List<PackageFile>)

/**
 * Writes a `.espeon` zip (manifest.json first, then the take files). Platform implementations
 * provide the actual zipping (Android: java.util.zip). Videos are already compressed, so
 * implementations should store them without recompressing.
 */
interface PackageWriter {
    /** Writes [plan] to [outputPath]. Throws if any listed file is missing. */
    fun write(plan: PackagePlan, outputPath: String)
}
