import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
    private static String rootDirectory;
    private static String defaultPage;
    private static int maxThreads;

    public static void main(String[] args) {
        Properties config = loadConfig("config.ini");
        int port = Integer.parseInt(config.getProperty("port", "8080"));
        rootDirectory = config.getProperty("root", "~/www/lab/html/").replace("~", System.getProperty("user.home"));
        defaultPage = config.getProperty("defaultPage", "index.html");
        maxThreads = Integer.parseInt(config.getProperty("maxThreads", "10"));

        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(new ClientHandler(clientSocket));
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }

    private static Properties loadConfig(String fileName) {
        Properties config = new Properties();
        try (InputStream inputStream = new FileInputStream(fileName)) {
            config.load(inputStream);
        } catch (IOException e) {
            System.err.println("Could not load config file, using default settings. Error: " + e.getMessage());
        }
        return config;
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {
   
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    System.err.println("Client closed the connection before sending a request.");
                    return; 
                }
                System.out.println(requestLine);
                
                // Split request line
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 3) {
                    sendBadRequest(writer);
                    return;
                }

                String method = requestParts[0];
                String resourcePath = requestParts[1];

                // Prevent directory traversal vulnerability
                if (resourcePath.contains("..")) {
                    sendBadRequest(writer);
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

                // Check for chunked transfer encoding request
                boolean isChunked = "yes".equalsIgnoreCase(headers.get("chunked"));

                // Handle different HTTP methods
                switch (method) {
                    case "GET":
                    case "HEAD":
                        serveResource(writer, clientSocket.getOutputStream(), resourcePath, method.equals("HEAD"), isChunked);
                        break;
                    case "POST":
                        // Parse parameters from the URL for POST requests
                        Map<String, String> postParameters = parseParameters(resourcePath);
                        System.out.println("POST parameters from URL: " + postParameters);
                    
                        // If there's a need to process the body, you can do so here
                        // Note: This example just reads and echoes the body for demonstration purposes
                        if (headers.containsKey("Content-Length")) {
                            int contentLength = Integer.parseInt(headers.get("Content-Length"));
                            char[] body = new char[contentLength];
                            reader.read(body, 0, contentLength);
                            String requestBody = new String(body);
                    
                            // Log the POST body (if necessary for your application)
                            System.out.println("POST body: " + requestBody);
                    
                            // Echo the requestBody back in the response (for demonstration)
                            writer.write("HTTP/1.1 200 OK\r\n");
                            writer.write("Content-Type: text/plain\r\n");
                            writer.write("Content-Length: " + requestBody.length() + "\r\n");
                            writer.write("\r\n");
                            writer.write(requestBody);
                            writer.flush();
                        } else {
                            sendBadRequest(writer);
                        }
                        break;
                    case "TRACE":
                        StringBuilder traceResponse = new StringBuilder();
                    
                        // Reconstruct the request line and headers for the TRACE response body
                        traceResponse.append(requestLine).append("\r\n");
                        for (Map.Entry<String, String> header : headers.entrySet()) {
                            traceResponse.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                        }
                        traceResponse.append("\r\n"); // End of headers in the reconstructed request
                    
                        // Convert the reconstructed request to bytes
                        byte[] traceResponseBody = traceResponse.toString().getBytes(StandardCharsets.UTF_8);
                    
                        // Send the TRACE response headers
                        writer.write("HTTP/1.1 200 OK\r\n");
                        writer.write("Content-Type: application/octet-stream\r\n");
                        writer.write("Content-Length: " + traceResponseBody.length + "\r\n");
                        writer.write("\r\n"); // End of headers in the response
                        writer.flush();
                    
                        // Write the TRACE response body
                        clientSocket.getOutputStream().write(traceResponseBody);
                        clientSocket.getOutputStream().flush();
                        break;
                        

                    default:
                        sendNotImplemented(writer);
                        break;
                }

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

        private void serveResource(BufferedWriter writer, OutputStream out, String resourcePath, boolean headOnly, boolean isChunked) throws IOException {
            // Normalize and resolve the file path
            Path path = Paths.get(rootDirectory).resolve(resourcePath.substring(1)).normalize();
            if (!path.startsWith(Paths.get(rootDirectory))) {
                sendBadRequest(writer);
                return;
            }

            // Serve default page if necessary
            if (Files.isDirectory(path)) {
                path = path.resolve(defaultPage);
            }

            

            if (Files.exists(path) && !Files.isDirectory(path)) {
                String contentType = determineContentType(path);
                byte[] fileContent = Files.readAllBytes(path);
                
                if (isChunked) {
                    writer.write("HTTP/1.1 200 OK\r\n");
                    writer.write("Transfer-Encoding: chunked\r\n");
                    writer.write("Content-Type: " + contentType + "\r\n\r\n");
                    writer.flush();
                    
                    if (!headOnly) {
                        sendChunkedResponse(out, fileContent);
                    }
                } else {
                    writer.write("HTTP/1.1 200 OK\r\n");
                    writer.write("Content-Length: " + fileContent.length + "\r\n");
                    writer.write("Content-Type: " + contentType + "\r\n\r\n");
                    writer.flush();
                    
                    if (!headOnly) {
                        out.write(fileContent);
                        out.flush();
                    }
                }
                
                // Print response headers
                System.out.println("Response Header: HTTP/1.1 200 OK");
                if (isChunked) {
                    System.out.println("Response Header: Transfer-Encoding: chunked");
                } else {
                    System.out.println("Response Header: Content-Length: " + fileContent.length);
                }
                System.out.println("Response Header: Content-Type: " + contentType);
            } else {
                sendNotFound(writer);
            }
        }

        private void sendChunkedResponse(OutputStream out, byte[] data) throws IOException {
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

        private String determineContentType(Path path) {
            String contentType = "application/octet-stream"; // Default content type
            String fileName = path.getFileName().toString().toLowerCase();
        
            if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                contentType = "text/html";
            } else if (fileName.endsWith(".jpg") || (fileName.endsWith(".png")) || (fileName.endsWith(".gif")) || (fileName.endsWith(".bmp"))) {
                contentType = "image";
            } else if (fileName.endsWith(".ico")) {
                contentType = "icon";
            }
        
            return contentType;
        }
        
        private Map<String, String> parseParameters(String url) {
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

        private void sendBadRequest(BufferedWriter writer) throws IOException {
            writer.write("HTTP/1.1 400 Bad Request\r\n\r\n");
            writer.flush();
        }

        private void sendNotFound(BufferedWriter writer) throws IOException {
            writer.write("HTTP/1.1 404 Not Found\r\n\r\n");
            writer.flush();
        }

        private void sendNotImplemented(BufferedWriter writer) throws IOException {
            writer.write("HTTP/1.1 501 Not Implemented\r\n\r\n");
            writer.flush();
        }
    }
}
