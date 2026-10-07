package com.deivid22srk.gtavserver.core

/**
 * Frases e descrições do HTTPStatus do CPython 3.12.14 (Lib/http/__init__.py).
 * Usadas por send_response/send_error exatamente como em BaseHTTPRequestHandler.
 */
object HttpStatus {
    val phrases: Map<Int, String> = mapOf(
        100 to "Continue", 101 to "Switching Protocols", 102 to "Processing", 103 to "Early Hints",
        200 to "OK", 201 to "Created", 202 to "Accepted", 203 to "Non-Authoritative Information",
        204 to "No Content", 205 to "Reset Content", 206 to "Partial Content",
        207 to "Multi-Status", 208 to "Already Reported", 226 to "IM Used",
        300 to "Multiple Choices", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other",
        304 to "Not Modified", 305 to "Use Proxy", 307 to "Temporary Redirect", 308 to "Permanent Redirect",
        400 to "Bad Request", 401 to "Unauthorized", 402 to "Payment Required", 403 to "Forbidden",
        404 to "Not Found", 405 to "Method Not Allowed", 406 to "Not Acceptable",
        407 to "Proxy Authentication Required", 408 to "Request Timeout", 409 to "Conflict",
        410 to "Gone", 411 to "Length Required", 412 to "Precondition Failed",
        413 to "Request Entity Too Large", 414 to "Request-URI Too Long",
        415 to "Unsupported Media Type", 416 to "Requested Range Not Satisfiable",
        417 to "Expectation Failed", 418 to "I'm a Teapot", 421 to "Misdirected Request",
        422 to "Unprocessable Entity", 423 to "Locked", 424 to "Failed Dependency",
        425 to "Too Early", 426 to "Upgrade Required", 428 to "Precondition Required",
        429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
        451 to "Unavailable For Legal Reasons", 500 to "Internal Server Error",
        501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
        504 to "Gateway Timeout", 505 to "HTTP Version Not Supported",
        506 to "Variant Also Negotiates", 507 to "Insufficient Storage", 508 to "Loop Detected",
        510 to "Not Extended", 511 to "Network Authentication Required",
    )

    val descriptions: Map<Int, String> = mapOf(
        200 to "Request fulfilled, document follows",
        206 to "Partial content follows",
        301 to "Object moved permanently -- see URI list",
        304 to "Document has not changed since given time",
        400 to "Bad request syntax or unsupported method",
        404 to "Nothing matches the given URI",
        413 to "Entity is too large",
        414 to "URI is too long",
        416 to "Cannot satisfy request range",
        431 to "The server is unwilling to process the request because its header fields are too large",
        501 to "Server does not support this operation",
        505 to "Cannot fulfill request",
    )

    fun phrase(code: Int): String = phrases[code] ?: "???"
    fun description(code: Int): String = descriptions[code] ?: "???"
}
