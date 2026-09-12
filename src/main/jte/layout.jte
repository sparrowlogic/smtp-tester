@import gg.jte.Content
@param String basePath
@param String title
@param Content content
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title} · smtp-tester</title>
    <link rel="stylesheet" href="${basePath}/css/app.css">
    <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'><text y='14' font-size='14'>&#9993;</text></svg>">
</head>
<body>
<header class="topbar">
    <a class="brand" href="${basePath}/">&#9993; smtp-tester</a>
    <span class="brand-note">SMTP :1025 &middot; HTTP :8025 &middot; MCP ${basePath}/mcp</span>
</header>
<main>
    ${content}
</main>
<script src="${basePath}/js/app.js" defer></script>
</body>
</html>
