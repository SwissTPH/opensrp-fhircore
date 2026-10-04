import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.util.Properties

fun readProperties(file: String): Properties {
  val properties = Properties()
  val localProperties = File(file)
  if (localProperties.isFile) {
    InputStreamReader(FileInputStream(localProperties), Charsets.UTF_8).use { reader ->
      properties.load(reader)
    }
  } else {
    throw FileNotFoundException("\u001B[34mFile $file not found\u001B[0m")
  }

  return properties
}

/**
 * Secret Mapbox token used only for authenticated Maven downloads from
 * https://api.mapbox.com/downloads/v2/releases/maven
 *
 * Prefer MAPBOX_DOWNLOADS_TOKEN; fall back to MAPBOX_SDK_TOKEN when it is a secret (sk.) token.
 * Public tokens (pk.*) cannot download private Mapbox packages.
 */
fun resolveMapboxDownloadsToken(): String? {
  val localPropsFile =
    (project.properties["localPropertiesFile"] ?: "${rootProject.projectDir}/local.properties")
      .toString()
  val localProps =
    try {
      readProperties(localPropsFile)
    } catch (_: FileNotFoundException) {
      Properties()
    }

  return sequenceOf(
      System.getenv("MAPBOX_DOWNLOADS_TOKEN"),
      System.getenv("MAPBOX_SDK_TOKEN"),
      localProps.getProperty("MAPBOX_DOWNLOADS_TOKEN"),
      localProps.getProperty("MAPBOX_SDK_TOKEN"),
    )
    .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .firstOrNull { token ->
      token.startsWith("sk.") &&
        !token.contains("REPLACE", ignoreCase = true) &&
        token !in setOf("dummy", "sample_MAPBOX_SDK_TOKEN")
    }
}

val mapboxDownloadsToken = resolveMapboxDownloadsToken()

allprojects {
  repositories {
    // Only register the private Mapbox Maven repo when a valid secret token is available.
    // Current deps (kujaku + mapbox-sdk-turf from Maven Central) resolve without it.
    if (mapboxDownloadsToken != null) {
      maven {
        url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
        credentials.username = "mapbox"
        credentials.password = mapboxDownloadsToken
        authentication.create<BasicAuthentication>("basic")
      }
    } else {
      logger.info(
        "Mapbox secret downloads token not set; skipping private Mapbox Maven repository. " +
          "Public Mapbox artifacts will resolve from Maven Central. " +
          "To enable private downloads, set MAPBOX_DOWNLOADS_TOKEN (sk.… with DOWNLOADS:READ) " +
          "in the environment or android/local.properties.",
      )
    }
  }
}
