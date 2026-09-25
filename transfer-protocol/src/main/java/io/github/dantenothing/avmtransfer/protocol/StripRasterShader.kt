package io.github.dantenothing.avmtransfer.protocol

/** Shared by OES playback and Media3's 2D export effect. All arguments are bottom-left UVs. */
object StripRasterShader {
    // readEncodedRaster is supplied by the caller, including the OES transform when applicable.
    // Interpolate in logical row space: texture filtering alone would blend unrelated column edges.
    val sampling = """
        uniform vec2 uRasterSource;
        uniform vec4 uRasterStorage;
        vec2 encodedRowUv(float x, float y) {
          float column = floor(y / uRasterStorage.z);
          float row = y - column * uRasterStorage.z;
          return vec2((column * uRasterSource.x + x + 0.5) / uRasterStorage.x,
                      1.0 - (row + 0.5) / uRasterStorage.y);
        }
        vec4 sampleLogicalRaster(vec2 logicalUv) {
          if (uRasterStorage.w < 0.5) return readEncodedRaster(logicalUv);
          vec2 pixel = vec2(logicalUv.x, 1.0 - logicalUv.y) * uRasterSource - vec2(0.5);
          pixel = clamp(pixel, vec2(0.0), uRasterSource - vec2(1.0));
          float y0 = floor(pixel.y);
          float y1 = min(y0 + 1.0, uRasterSource.y - 1.0);
          vec4 first = readEncodedRaster(encodedRowUv(pixel.x, y0));
          vec4 second = readEncodedRaster(encodedRowUv(pixel.x, y1));
          return mix(first, second, pixel.y - y0);
        }
    """.trimIndent()
}
