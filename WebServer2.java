import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Handles server configuration
class Config {
    private final Properties properties;

    public Config(String fileName) {
        properties = new Properties();
        try (InputStream inputStream = new FileInputStream(fileName)) {
            properties.load(inputStream);
        } catch (IOException e) {
            System.err.println("Could not load config file, using default settings. Error: " + e.getMessage());
        }
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
}

// Handles HTTP response construction and sending
class HttpResponse {
    private final BufferedWriter writer;
    private final OutputStream out;

    public HttpResponse(Socket clientSocket) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        out = clientSocket.getOutputStream();
    }

    public void sendBadRequest() throws IOException {
        System.out.println("Response Header: HTTP/1.1 400 Bad Request");
        writer.write("HTTP/1.1 400 Bad Request\r\n\r\n");
        writer.flush();
    }

    public void sendNotFound() throws IOException {
        System.out.println("Response Header: HTTP/1.1 404 Not Found");
        writer.write("HTTP/1.1 404 Not Found\r\n\r\n");
        writer.flush();
    }

    public void sendNotImplemented() throws IOException {
        System.out.println("Response Header: HTTP/1.1 501 Not Implemented");
        writer.write("HTTP/1.1 501 Not Implemented\r\n\r\n");
        writer.flush();
    }

    public void sendChunkedResponse(byte[] data, String contentType) throws IOException {
        System.out.println("Response Header: HTTP/1.1 200 OK");
        System.out.println("Response Header: Transfer-Encoding: chunked");
        System.out.println("Response Header: Content-Type: " + contentType);
        writer.write("HTTP/1.1 200 OK\r\n");
        writer.write("Transfer-Encoding: chunked\r\n");
        writer.write("Content-Type: " + contentType + "\r\n\r\n");
        writer.flush();

        int offset = 0;
        while (offset < data.length) {
            int chunkSize = Math.min(4096, data.length - offset); // 4KB chunk size
            String chunkHeader = Integer.toHexString(chunkSize) + "\r\n";
            out.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
            out.write(data, offset, chunkSize);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            offset += chunkSize;
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8)); // End of chunks
        out.flush();
    }

    public void sendResponse(byte[] data, String contentType, boolean headOnly) throws IOException {
        System.out.println("Response Header: HTTP/1.1 200 OK");
        System.out.println("Response Header: Content-Length: " + data.length);
        System.out.println("Response Header: Content-Type: " + contentType);
        writer.write("HTTP/1.1 200 OK\r\n");
        writer.write("Content-Length: " + data.length + "\r\n");
        writer.write("Content-Type: " + contentType + "\r\n\r\n");
        writer.flush();

        if (!headOnly) {
            out.write(data);
            out.flush();
        }
    }
}

// Provides utility functions
class HttpUtils {
    public static String determineContentType(Path path) {
        String contentType = "application/octet-stream"; // Default content type
        String fileName = path.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            contentType = "text/html";
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") || fileName.endsWith(".gif") || fileName.endsWith(".bmp")) {
            contentType = "image";
        } else if (fileName.endsWith(".ico")) {
            contentType = "icon";
        }

        return contentType;
    }

    public static Map<String, String> parseParameters(String url) {
        Map<String, String> parameters = new HashMap<>();
        try {
            String[] urlParts = url.split("\\?");
            if (urlParts.length > 1) {
                String query = urlParts[1];
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) {
                        parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()), URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()));
                    } else {
                        parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()), "");
                    }
                }
            }
        } catch (UnsupportedEncodingException e) {
            System.err.println("Error decoding URL parameters: " + e.getMessage());
        }
        return parameters;
    }
}

// Handles individual client requests
class RequestHandler implements Runnable {
    private final Socket clientSocket;
    private final String rootDirectory;
    private final String defaultPage;

    public RequestHandler(Socket socket, String rootDir, String defaultPg) {
        this.clientSocket = socket;
        this.rootDirectory = rootDir;
        this.defaultPage = defaultPg;
    }

    @Override
    public void run() {
        try {
            processRequest();
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    private void processRequest() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        HttpResponse response = new HttpResponse(clientSocket);

        String requestLine = reader.readLine();
        if (requestLine == null) {
            System.err.println("Client closed the connection before sending a request.");
            return;
        }
        System.out.println(requestLine);

        // Split request line
        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 3) {
            response.sendBadRequest();
            return;
        }

        String method = requestParts[0];
        String resourcePath = requestParts[1];

        // Prevent directory traversal vulnerability
        if (resourcePath.contains("..")) {
            response.sendBadRequest();
            return;
        }

        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while (!(headerLine = reader.readLine()).isEmpty()) {
            String[] headerParts = headerLine.split(": ");
            if (headerParts.length == 2) {
                headers.put(headerParts[0].trim(), headerParts[1].trim());
                System.out.println("Request Header: " + headerParts[0].trim() + ": " + headerParts[1].trim()); // Print each request header
            }
        }

        // Handle different HTTP methods
        switch (method) {
            case "GET":
            case "HEAD":
                serveResource(response, resourcePath, method.equals("HEAD"));
                break;
            case "POST":
                handlePostRequest(reader, response, headers, resourcePath);
                break;
            case "TRACE":
                handleTraceRequest(response, requestLine, headers);
                break;
            default:
                response.sendNotImplemented();
                break;
        }
    }

    private void serveResource(HttpResponse response, String resourcePath, boolean headOnly) throws IOException {
        // Normalize and resolve the file path
        Path path = Paths.get(rootDirectory).resolve(resourcePath.substring(1)).normalize();
        if (!path.startsWith(Paths.get(rootDirectory))) {
            response.sendBadRequest();
            return;
        }

        // Serve default page if necessary
        if (Files.isDirectory(path)) {
            path = path.resolve(defaultPage);
        }

        if (Files.exists(path) && !Files.isDirectory(path)) {
            String contentType = HttpUtils.determineContentType(path);
            byte[] fileContent = Files.readAllBytes(path);
            response.sendResponse(fileContent, contentType, headOnly);
        } else {
            response.sendNotFound();
        }
    }

    private void handlePostRequest(BufferedReader reader, HttpResponse response, Map<String, String> headers, String resourcePath) throws IOException {
        Map<String, String> postParameters = HttpUtils.parseParameters(resourcePath);
        System.out.println("POST parameters from URL: " + postParameters);

        if (headers.containsKey("Content-Length")) {
            int contentLength = Integer.parseInt(headers.get("Content-Length"));
            char[] body = new char[contentLength];
            reader.read(body, 0, contentLength);
            String requestBody = new String(body);

            // Log the POST body (if necessary for your application)
            System.out.println("POST body: " + requestBody);

            // Echo the requestBody back in the response (for demonstration)
            response.sendResponse(requestBody.getBytes(StandardCharsets.UTF_8), "text/plain", false);
        } else {
            response.sendBadRequest();
        }
    }

    private void handleTraceRequest(HttpResponse response, String requestLine, Map<String, String> headers) throws IOException {
        StringBuilder traceResponse = new StringBuilder();

        // Reconstruct the request line and headers for the TRACE response body
        traceResponse.append(requestLine).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            traceResponse.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        traceResponse.append("\r\n"); // End of headers in the reconstructed request

        // Convert the reconstructed request to bytes
        byte[] traceResponseBody = traceResponse.toString().getBytes(StandardCharsets.UTF_8);

        // Send the TRACE response
        response.sendResponse(traceResponseBody, "application/octet-stream", false);
    }
}

public class WebServer2 {
    private String rootDirectory;
    private String defaultPage;
    private int maxThreads;

    public WebServer2() {
        Config config = new Config("config.ini");
        rootDirectory = config.getProperty("root", "~/www/lab/html/").replace("~", System.getProperty("user.home"));
        defaultPage = config.getProperty("defaultPage", "index.html");
        maxThreads = Integer.parseInt(config.getProperty("maxThreads", "10"));
    }

    public void start(int port) {
        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(new RequestHandler(clientSocket, rootDirectory, defaultPage));
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }

    public static void main(String[] args) {
        WebServer2 webServer = new WebServer2();
        Config config = new Config("config.ini");
        int port = Integer.parseInt(config.getProperty("port", "8080"));
        webServer.start(port);
    }
}
