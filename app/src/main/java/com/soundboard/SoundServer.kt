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
            uri == "/recent" -> handleRecent()
            uri == "/ui" -> handleUI()
            uri == "/" -> handleRoot()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"error": "Not found", "endpoints": ["/play?file=<name>.mp3", "/stop", "/status", "/recent", "/ui"]}"""
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
            RecentSoundsManager.addSound(filename)
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

    private fun handleRecent(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            RecentSoundsManager.toJson()
        )
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
                    <li><code>GET /recent</code> - Get recently played sounds</li>
                    <li><code>GET /ui</code> - Interactive web UI</li>
                </ul>
                <h2>Example:</h2>
                <pre>curl "http://&lt;this-ip&gt;:8080/play?file=mgs-alert.mp3"</pre>
                <p><a href="/ui">Open Interactive UI</a></p>
            </body>
            </html>
            """.trimIndent()
        )
    }

    private fun handleUI(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            generateWebUI()
        )
    }

    private fun generateWebUI(): String = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Soundboard</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #1a1a2e;
            min-height: 100vh;
            padding: 20px;
        }

        h1 {
            color: #fff;
            text-align: center;
            margin-bottom: 30px;
            font-size: 2em;
        }

        .button-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
            gap: 15px;
            max-width: 1200px;
            margin: 0 auto;
        }

        .sound-button {
            padding: 20px 15px;
            border: none;
            border-radius: 12px;
            cursor: pointer;
            font-size: 14px;
            font-weight: 600;
            color: #fff;
            text-shadow: 1px 1px 2px rgba(0,0,0,0.3);
            box-shadow: 0 4px 6px rgba(0,0,0,0.3),
                        inset 0 1px 0 rgba(255,255,255,0.2);
            transition: transform 0.1s, box-shadow 0.1s;
            word-wrap: break-word;
            text-align: center;
            min-height: 80px;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .sound-button:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 12px rgba(0,0,0,0.4),
                        inset 0 1px 0 rgba(255,255,255,0.2);
        }

        .sound-button:active {
            transform: translateY(1px);
            box-shadow: 0 2px 4px rgba(0,0,0,0.3),
                        inset 0 1px 0 rgba(255,255,255,0.2);
        }

        .empty-state {
            color: #888;
            text-align: center;
            padding: 40px;
            font-size: 1.2em;
            grid-column: 1 / -1;
        }

        .controls {
            text-align: center;
            margin-bottom: 20px;
        }

        .stop-button {
            background: #e74c3c;
            color: white;
            border: none;
            padding: 10px 30px;
            border-radius: 8px;
            font-size: 16px;
            cursor: pointer;
            margin-right: 10px;
        }

        .stop-button:hover {
            background: #c0392b;
        }

        .refresh-button {
            background: #3498db;
            color: white;
            border: none;
            padding: 10px 30px;
            border-radius: 8px;
            font-size: 16px;
            cursor: pointer;
        }

        .refresh-button:hover {
            background: #2980b9;
        }
    </style>
</head>
<body>
    <h1>Soundboard</h1>
    <div class="controls">
        <button class="stop-button" onclick="stopSound()">Stop</button>
        <button class="refresh-button" onclick="loadSounds()">Refresh</button>
    </div>
    <div id="buttons" class="button-grid"></div>
    <script>
        async function loadSounds() {
            try {
                const response = await fetch('/recent');
                const data = await response.json();
                renderButtons(data.sounds);
            } catch (error) {
                console.error('Failed to load sounds:', error);
            }
        }

        function renderButtons(sounds) {
            const container = document.getElementById('buttons');

            if (!sounds || sounds.length === 0) {
                container.innerHTML = '<div class="empty-state">No sounds played yet.<br>Use /play?file=sound.mp3 to add sounds.</div>';
                return;
            }

            container.innerHTML = sounds.map(sound =>
                '<button class="sound-button" style="background-color: ' + sound.color + '" onclick="playSound(\'' + sound.filename.replace(/'/g, "\\'") + '\')">' +
                escapeHtml(sound.displayName) +
                '</button>'
            ).join('');
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        async function playSound(filename) {
            try {
                await fetch('/play?file=' + encodeURIComponent(filename));
                setTimeout(loadSounds, 100);
            } catch (error) {
                console.error('Failed to play sound:', error);
            }
        }

        async function stopSound() {
            try {
                await fetch('/stop');
            } catch (error) {
                console.error('Failed to stop sound:', error);
            }
        }

        loadSounds();
        setInterval(loadSounds, 5000);
    </script>
</body>
</html>
    """.trimIndent()
}
