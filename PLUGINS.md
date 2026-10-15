# Xstream plugins — format (plugin API 1)

A Xstream plugin is **one JavaScript file** (ES5 style, run by the Rhino engine inside the app).
It is installed in Xstream › Settings › Content & Discovery › **Plugins** (by link to the `.js` file, or by pasting the code).
Every enabled plugin is asked for streams at the same time as the installed addons; its links appear in **Sources**,
go through the same Source Test ranking, stream filters, quality and language picking, and can be played, raced,
saved for instant play, downloaded and shared in Watch together like any addon link.

## 1. The file

```js
// Required: who the plugin is.
var plugin = {
  id: "com.example.myplugin",        // unique, never changes between versions
  name: "My Plugin",                 // shown in Sources (like an addon name)
  version: "1.0.0",
  api: 1,                            // plugin API version (this document)
  description: "What it finds",
  author: "Your name",
  types: ["movie", "series"]         // what it can find streams for
};

// Required: find the streams of one title. Must return an array (empty = nothing found).
function getStreams(req) {
  // req = {
  //   type:    "movie" | "series",
  //   id:      "tt0111161"  (movie)  |  "tt0944947:1:3"  (series: imdbId:season:episode),
  //   imdbId:  "tt0111161",
  //   season:  1,          // series only
  //   episode: 3,          // series only
  //   title:   "…",        // sometimes present
  //   year:    "1994"      // sometimes present
  // }
  var res = http.get("https://example.org/api?imdb=" + req.imdbId, { "Accept": "application/json" });
  if (res.status !== 200) { log("HTTP " + res.status); return []; }
  var data = JSON.parse(res.body);
  return data.items.map(function (it) {
    return {
      url: it.link,                                   // required: direct video file, or HLS (.m3u8) / DASH (.mpd)
      name: "My Plugin 1080p",                        // first line in Sources (quality is read from it too)
      title: it.fileName + "\nHindi · 2.1 GB",        // more lines: file name, languages, size…
      headers: { "Referer": "https://example.org/" }, // optional: request headers the video needs (also for HLS pieces)
      behaviorHints: { filename: it.fileName, videoSize: it.bytes } // optional but recommended
    };
  });
}
```

The returned objects are **Stremio stream objects** (`url`, `name`, `title`, `description`, `subtitles`, `behaviorHints`
with `filename`, `videoSize`, `bingeGroup`, `proxyHeaders.request`), plus the shorthand `headers`.

**Tips for good results in Xstream**
- Put the quality (2160p / 1080p / 720p / 480p) and the audio languages (Hindi, English, Tamil…) in `name` / `title`
  or the file name — Xstream's quality buttons, language order and Source Test groups are built from them.
- Give `behaviorHints.filename` and `videoSize` whenever you know them (saved-video keys, resume, downloads, "same file" detection).
- `bingeGroup` = a stable label for the provider/server (it becomes the provider group in the Source Test ranking).
- Return plain video files or HLS/DASH links that play directly. Never return a web page (it is detected and hidden as dead).
- Signed links that expire: fine (they are refreshed by asking the plugin again).

## 2. What a plugin can use

| Helper | What it does |
|---|---|
| `http.get(url, headers?)` | GET request. Returns `{ status, url, body, headers }` (`url` = final URL after redirects, `body` = text, `headers` = object of strings). |
| `http.post(url, body, headers?)` | POST request; `body` is a string or an object (sent as JSON unless `Content-Type` says otherwise). Same result. |
| `log(message)` | Writes to the plugin log (Settings › Plugins › Log). |
| `atob(s)` / `btoa(s)` | Base64 decode / encode. |
| `JSON`, `RegExp`, `Math`, `Date`, strings, arrays… | Standard JavaScript as Rhino runs it: write **ES5** (`var`, `function`), arrow functions and `let`/`const` are fine. Avoid classes, template strings, destructuring, spread, `async`/`await`. |

Not available: `fetch`, `setTimeout`, promises/`async`, DOM, Node modules, Java classes.
Requests are **synchronous** (just call `http.get` and use the result). A browser User-Agent is sent unless you set one.
Cookies set by servers are kept like in a browser.

## 3. Limits
- One `getStreams` call may take at most **25 seconds** (then it is stopped and counts as "no links").
- Keep it fast: ask as few pages as possible; Xstream starts playing as soon as the first good links arrive.
- Errors are caught; the reason appears in the plugin log. A plugin can't crash the app.

## 4. Updating
Install the same link again (or tap **Update** in Plugins): the plugin with the same `id` is replaced.
Raise `version` with every change.
