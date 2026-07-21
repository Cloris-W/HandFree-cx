import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';

const rootDirectory = resolve(process.cwd());
const mimeTypes = {
    '.css': 'text/css; charset=utf-8',
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml'
};

const server = createServer(async (request, response) => {
    const requestUrl = new URL(request.url ?? '/', 'http://localhost:3000');
    const relativePath = decodeURIComponent(requestUrl.pathname === '/' ? '/index.html' : requestUrl.pathname);
    const filePath = resolve(rootDirectory, `.${relativePath}`);

    if (filePath !== rootDirectory && !filePath.startsWith(`${rootDirectory}${sep}`)) {
        response.writeHead(403);
        response.end('Forbidden');
        return;
    }

    try {
        const file = await readFile(filePath);
        response.writeHead(200, {
            'Content-Type': mimeTypes[extname(filePath)] ?? 'application/octet-stream',
            'Cache-Control': 'no-store'
        });
        response.end(file);
    } catch {
        response.writeHead(404);
        response.end('Not found');
    }
});

server.listen(3000, '127.0.0.1', () => {
    console.log('HandFree demo is available at http://localhost:3000');
});
