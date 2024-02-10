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
    private static final Map<String, List<String>> storedParameters = new HashMap<>();

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
    
    private static Map<String, List<String>> storeParameters(Map<String, String> parameters) {
        Map<String, List<String>> addedParameters = new HashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            List<String> values = storedParameters.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            values.add(entry.getValue());
            addedParameters.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
        return addedParameters;
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
                        // Parse parameters from the URL
                            Map<String, String> parameters = parseParameters(resourcePath);
                        
                            // Read and parse parameters from the body if Content-Length is present
                            if (headers.containsKey("Content-Length")) {
                                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                                char[] body = new char[contentLength];
                                reader.read(body, 0, contentLength);
                                String requestBody = new String(body);
                                System.out.println("POST body: " + requestBody);
                        
                                // Parse and merge body parameters with URL parameters
                                parameters.putAll(parseParameters("?" + requestBody));
                            }
                            
                            Map<String, List<String>> addedParameters = storeParameters(parameters);
                        
                            // Serve the params_info.html page with parameter details
                            generateParamsInfoPage(addedParameters);
                            serveParamsInfoPage(writer, clientSocket.getOutputStream());
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
                        System.out.println("Response Header: HTTP/1.1 200 OK");
                        System.out.println("Response Header: Content-Type: application/octet-stream");
                        System.out.println("Response Header: Content-Length: " + traceResponseBody.length);
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
                // Catch any unexpected exceptions and respond with a 500 Internal Server Error
                System.err.println("Unexpected error handling client: " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }

        private void generateParamsInfoPage(Map<String, List<String>> parameters) throws IOException {
            String filePath = rootDirectory + "params_info.html"; // Define the file path for params_info.html
            try (BufferedWriter fileWriter = Files.newBufferedWriter(Paths.get(filePath), StandardCharsets.UTF_8)) {
                StringBuilder builder = new StringBuilder();
                builder.append("<!DOCTYPE html>\n")
                       .append("<html lang=\"en\">\n")
                       .append("<head>\n")
                       .append("    <meta charset=\"UTF-8\">\n")
                       .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                       .append("    <title>Submitted Parameters</title>\n")
                       .append("</head>\n")
                       .append("<body>\n")
                       .append("    <h1>Submitted Parameters</h1>\n")
                       .append("    <table border=\"1\">\n") // Start of the table
                       .append("        <tr>\n") // Table header row
                       .append("            <th>Parameter Name</th>\n")
                       .append("            <th>Value</th>\n")
                       .append("        </tr>\n");
        
                for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                    for (String value : entry.getValue()) {
                        builder.append("        <tr>\n") // Start of a table row for each parameter
                               .append("            <td>").append(entry.getKey()).append("</td>\n") // Parameter name
                               .append("            <td>").append(value).append("</td>\n") // Each value in the list
                               .append("        </tr>\n");
                    }
                }
        
                builder.append("    </table>\n") // End of the table
                       .append("</body>\n")
                       .append("</html>");
        
                fileWriter.write(builder.toString());
            }
        }
        

        private void serveParamsInfoPage(BufferedWriter writer, OutputStream out) throws IOException {
            String filePath = rootDirectory + "params_info.html"; // The path to params_info.html
            Path path = Paths.get(filePath);
        
            if (Files.exists(path) && !Files.isDirectory(path)) {
                byte[] fileContent = Files.readAllBytes(path);
                String contentType = "text/html"; // Content type for HTML
        
                writer.write("HTTP/1.1 200 OK\r\n");
                writer.write("Content-Type: " + contentType + "\r\n");
                writer.write("Content-Length: " + fileContent.length + "\r\n");
                writer.write("\r\n");
                writer.flush();
        
                out.write(fileContent);
                out.flush();
            } else {
                // If the file doesn't exist, send a 404 Not Found response
                sendNotFound(writer);
            }
        }
        
        

        private void serveResource(BufferedWriter writer, OutputStream out, String resourcePath, boolean headOnly, boolean isChunked) throws IOException {
            // Check if the path contains parameters and extract them
            String filePath = resourcePath;
            
            if (!headOnly && resourcePath.contains("?")) {
                int paramIndex = resourcePath.indexOf("?");
                String paramString = resourcePath.substring(paramIndex + 1);
                Map<String, String> requestParameters = parseParameters("?" + paramString); // Use your existing parseParameters method
                filePath = resourcePath.substring(0, paramIndex); // Exclude the parameters from the filePath
                storeParameters(requestParameters); // Store extracted parameters (implement this method if not already done)
            }
        
            // Normalize and resolve the file path
            Path path = Paths.get(rootDirectory).resolve(filePath.substring(1)).normalize();
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
                }
                System.out.println("Response Header: Content-Length: " + fileContent.length);
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
        
        private Map<String, String> parseParameters(String input) {
            Map<String, String> parameters = new HashMap<>();
            try {
                String[] parts = input.split("\\?");
                if (parts.length > 1) {
                    String query = parts[1];
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

        private void sendInternalServerError(BufferedWriter writer) throws IOException {
            writer.write("HTTP/1.1 500 Internal Server Error\r\n");
            writer.flush();
        }
    }
}