package com.deivid22srk.gtavserver.core

/**
 * Réplica do mecanismo de MIME do CPython 3.12 usado pelo servidor original:
 *
 * 1. SimpleHTTPRequestHandler.guess_type:
 *    - extensions_map (case-sensível, depois lower): {'.gz','.Z','.bz2','.xz'}
 *    - mimetypes.guess_type(path) [strict=True]
 *    - fallback 'application/octet-stream'
 * 2. mimetypes.MimeTypes.guess_type: suffix_map (loop lower), encodings_map
 *    (case-sensível), types_map estrita (case-sensível? não: checa ext em
 *    types_map[True] depois ext.lower()).
 *
 * Tabelas copiadas verbatim de Lib/mimetypes.py 3.12.14. Em Linux/CI o módulo
 * também pode ler /etc/mime.types (variação de plataforma que o ORIGINAL tem);
 * aqui usamos apenas a tabela padrão — ver docs/TESTES.md.
 */
object MimeTypes {

    // _suffix_map_default
    private val suffixMap = mapOf(
        ".svgz" to ".svg.gz",
        ".tgz" to ".tar.gz",
        ".taz" to ".tar.gz",
        ".tz" to ".tar.gz",
        ".tbz2" to ".tar.bz2",
        ".txz" to ".tar.xz",
    )

    // _encodings_map_default (case-SENSÍVEL)
    private val encodingsMap = mapOf(
        ".gz" to "gzip",
        ".Z" to "compress",
        ".bz2" to "bzip2",
        ".xz" to "xz",
        ".br" to "br",
    )

    // _types_map_default (strict). Chaves minúsculas (consulta é por ext.lower()).
    private val typesMap: Map<String, String> = mapOf(
        ".js" to "text/javascript",
        ".mjs" to "text/javascript",
        ".json" to "application/json",
        ".webmanifest" to "application/manifest+json",
        ".doc" to "application/msword",
        ".dot" to "application/msword",
        ".wiz" to "application/msword",
        ".nq" to "application/n-quads",
        ".nt" to "application/n-triples",
        ".bin" to "application/octet-stream",
        ".a" to "application/octet-stream",
        ".dll" to "application/octet-stream",
        ".exe" to "application/octet-stream",
        ".o" to "application/octet-stream",
        ".obj" to "application/octet-stream",
        ".so" to "application/octet-stream",
        ".oda" to "application/oda",
        ".pdf" to "application/pdf",
        ".p7c" to "application/pkcs7-mime",
        ".ps" to "application/postscript",
        ".ai" to "application/postscript",
        ".eps" to "application/postscript",
        ".trig" to "application/trig",
        ".m3u" to "application/vnd.apple.mpegurl",
        ".m3u8" to "application/vnd.apple.mpegurl",
        ".xls" to "application/vnd.ms-excel",
        ".xlb" to "application/vnd.ms-excel",
        ".ppt" to "application/vnd.ms-powerpoint",
        ".pot" to "application/vnd.ms-powerpoint",
        ".ppa" to "application/vnd.ms-powerpoint",
        ".pps" to "application/vnd.ms-powerpoint",
        ".pwz" to "application/vnd.ms-powerpoint",
        ".wasm" to "application/wasm",
        ".bcpio" to "application/x-bcpio",
        ".cpio" to "application/x-cpio",
        ".csh" to "application/x-csh",
        ".dvi" to "application/x-dvi",
        ".gtar" to "application/x-gtar",
        ".hdf" to "application/x-hdf",
        ".h5" to "application/x-hdf5",
        ".latex" to "application/x-latex",
        ".mif" to "application/x-mif",
        ".cdf" to "application/x-netcdf",
        ".nc" to "application/x-netcdf",
        ".p12" to "application/x-pkcs12",
        ".pfx" to "application/x-pkcs12",
        ".ram" to "application/x-pn-realaudio",
        ".pyc" to "application/x-python-code",
        ".pyo" to "application/x-python-code",
        ".sh" to "application/x-sh",
        ".shar" to "application/x-shar",
        ".swf" to "application/x-shockwave-flash",
        ".sv4cpio" to "application/x-sv4cpio",
        ".sv4crc" to "application/x-sv4crc",
        ".tar" to "application/x-tar",
        ".tcl" to "application/x-tcl",
        ".tex" to "application/x-tex",
        ".texi" to "application/x-texinfo",
        ".texinfo" to "application/x-texinfo",
        ".roff" to "application/x-troff",
        ".t" to "application/x-troff",
        ".tr" to "application/x-troff",
        ".man" to "application/x-troff-man",
        ".me" to "application/x-troff-me",
        ".ms" to "application/x-troff-ms",
        ".ustar" to "application/x-ustar",
        ".src" to "application/x-wais-source",
        ".xsl" to "application/xml",
        ".rdf" to "application/xml",
        ".wsdl" to "application/xml",
        ".xpdl" to "application/xml",
        ".zip" to "application/zip",
        ".3gp" to "audio/3gpp",
        ".3gpp" to "audio/3gpp",
        ".3g2" to "audio/3gpp2",
        ".3gpp2" to "audio/3gpp2",
        ".aac" to "audio/aac",
        ".adts" to "audio/aac",
        ".loas" to "audio/aac",
        ".ass" to "audio/aac",
        ".au" to "audio/basic",
        ".snd" to "audio/basic",
        ".mp3" to "audio/mpeg",
        ".mp2" to "audio/mpeg",
        ".opus" to "audio/opus",
        ".aif" to "audio/x-aiff",
        ".aifc" to "audio/x-aiff",
        ".aiff" to "audio/x-aiff",
        ".ra" to "audio/x-pn-realaudio",
        ".wav" to "audio/x-wav",
        ".avif" to "image/avif",
        ".bmp" to "image/bmp",
        ".gif" to "image/gif",
        ".ief" to "image/ief",
        ".jpg" to "image/jpeg",
        ".jpe" to "image/jpeg",
        ".jpeg" to "image/jpeg",
        ".heic" to "image/heic",
        ".heif" to "image/heif",
        ".png" to "image/png",
        ".svg" to "image/svg+xml",
        ".tiff" to "image/tiff",
        ".tif" to "image/tiff",
        ".ico" to "image/vnd.microsoft.icon",
        ".ras" to "image/x-cmu-raster",
        ".pnm" to "image/x-portable-anymap",
        ".pbm" to "image/x-portable-bitmap",
        ".pgm" to "image/x-portable-graymap",
        ".ppm" to "image/x-portable-pixmap",
        ".rgb" to "image/x-rgb",
        ".xbm" to "image/x-xbitmap",
        ".xpm" to "image/x-xpixmap",
        ".xwd" to "image/x-xwindowdump",
        ".eml" to "message/rfc822",
        ".mht" to "message/rfc822",
        ".mhtml" to "message/rfc822",
        ".nws" to "message/rfc822",
        ".css" to "text/css",
        ".csv" to "text/csv",
        ".html" to "text/html",
        ".htm" to "text/html",
        ".md" to "text/markdown",
        ".markdown" to "text/markdown",
        ".n3" to "text/n3",
        ".txt" to "text/plain",
        ".bat" to "text/plain",
        ".c" to "text/plain",
        ".h" to "text/plain",
        ".ksh" to "text/plain",
        ".pl" to "text/plain",
        ".srt" to "text/plain",
        ".rtx" to "text/richtext",
        ".tsv" to "text/tab-separated-values",
        ".vtt" to "text/vtt",
        ".py" to "text/x-python",
        ".rst" to "text/x-rst",
        ".etx" to "text/x-setext",
        ".sgm" to "text/x-sgml",
        ".sgml" to "text/x-sgml",
        ".vcf" to "text/x-vcard",
        ".xml" to "text/xml",
        ".mp4" to "video/mp4",
        ".mpeg" to "video/mpeg",
        ".m1v" to "video/mpeg",
        ".mpa" to "video/mpeg",
        ".mpe" to "video/mpeg",
        ".mpg" to "video/mpeg",
        ".mov" to "video/quicktime",
        ".qt" to "video/quicktime",
        ".webm" to "video/webm",
        ".avi" to "video/x-msvideo",
        ".movie" to "video/x-sgi-movie",
    )

    // SimpleHTTPRequestHandler.extensions_map (3.12)
    private val extensionsMap = mapOf(
        ".gz" to "application/gzip",
        ".Z" to "application/octet-stream",
        ".bz2" to "application/x-bzip2",
        ".xz" to "application/x-xz",
    )

    /** Réplica de SimpleHTTPRequestHandler.guess_type(path). */
    fun guessType(path: String): String {
        val (base, rawExt) = PythonHttp.splitExt(path)
        var ext = rawExt
        if (extensionsMap.containsKey(ext)) return extensionsMap[ext]!!
        ext = ext.lowercase(Locale_INDEPENDENT)
        if (extensionsMap.containsKey(ext)) return extensionsMap[ext]!!
        val guess = mimeGuess(base, ext)
        return guess ?: "application/octet-stream"
    }

    /** Réplica de mimetypes.MimeTypes.guess_type (strict=True). */
    private fun mimeGuess(baseIn: String, extIn: String): String? {
        var base = baseIn
        var ext = extIn
        // suffix_map loop (por ext.lower(); python aplica splitext(base + destino))
        while (suffixMap.containsKey(ext.lowercase(Locale_INDEPENDENT))) {
            val target = suffixMap[ext.lowercase(Locale_INDEPENDENT)]!!
            val joined = base + target
            val split = PythonHttp.splitExt(joined)
            base = split.first
            ext = split.second
        }
        var encoding: String? = null
        if (encodingsMap.containsKey(ext)) { // case-SENSÍVEL
            encoding = encodingsMap[ext]
            val split = PythonHttp.splitExt(base)
            base = split.first
            ext = split.second
        }
        ext = ext.lowercase(Locale_INDEPENDENT)
        if (typesMap.containsKey(ext)) return typesMap[ext]
        // strict=True: sem common_types
        return null
    }

    private val Locale_INDEPENDENT = java.util.Locale.ROOT
}
