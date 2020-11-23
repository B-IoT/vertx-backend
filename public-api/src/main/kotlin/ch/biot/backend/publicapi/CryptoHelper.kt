package ch.biot.backend.publicapi

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

internal object CryptoHelper {
  @Throws(IOException::class)
  internal fun publicKey(): String = read("public_key.pem")

  @Throws(IOException::class)
  internal fun privateKey(): String = read("private_key.pem")

  @Throws(IOException::class)
  private fun read(filename: String): String {
    var path = Paths.get("public-api", filename)
    if (!path.toFile().exists()) {
      path = Paths.get("..", "public-api", filename)
    }
    return Files.readAllLines(path, StandardCharsets.UTF_8).joinToString(separator = "\n")
  }
}
