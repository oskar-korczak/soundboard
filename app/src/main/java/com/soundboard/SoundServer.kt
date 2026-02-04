package com.soundboard

import fi.iki.elonen.NanoHTTPD

class SoundServer(port: Int, private val soundPlayer: SoundPlayer) : NanoHTTPD(port) {

    companion object {
        private const val BASE_URL = "https://www.myinstants.com/media/sounds/"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parms

        return when {
            uri == "/play" -> handlePlay(params)
            uri == "/stop" -> handleStop()
            uri == "/status" -> handleStatus()
            uri == "/" -> handleRoot()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error": "Not found", "endpoints": ["/play?file=<name>.mp3", "/stop", "/status"]}"""
            )
        }
    }

    private fun handlePlay(params: Map<String, String>): Response {
        val filename = params["file"]

        if (filename.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error": "Missing 'file' parameter", "usage": "/play?file=example.mp3"}"""
            )
        }

        val url = BASE_URL + filename

        return try {
            soundPlayer.play(url)
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status": "playing", "file": "$filename", "url": "$url"}"""
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error": "Failed to play sound", "message": "${e.message}"}"""
            )
        }
    }

    private fun handleStop(): Response {
        soundPlayer.stop()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status": "stopped"}"""
        )
    }

    private fun handleStatus(): Response {
        val isPlaying = soundPlayer.isPlaying()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"server": "running", "playing": $isPlaying}"""
        )
    }

    private fun handleRoot(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            """
            <!DOCTYPE html>
            <html>
            <head><title>Soundboard</title></head>
            <body>
                <h1>Soundboard Server</h1>
                <h2>Endpoints:</h2>
                <ul>
                    <li><code>GET /play?file=&lt;filename&gt;.mp3</code> - Play a sound from myinstants.com</li>
                    <li><code>GET /stop</code> - Stop current playback</li>
                    <li><code>GET /status</code> - Get server status</li>
                </ul>
                <h2>Example:</h2>
                <pre>curl "http://&lt;this-ip&gt;:8080/play?file=mgs-alert.mp3"</pre>
            </body>
            </html>
            """.trimIndent()
        )
    }
}
