
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
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    System.err.println("Exception accepting client connection: " + e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executor.shutdown();
            System.out.println("Server is shutting down.");
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {

                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }
                System.out.println(requestLine);

                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 3) {
                    sendBadRequest(writer);
                    return;
                }

                String method = requestParts[0];
                String resourcePath = requestParts[1];
                String httpVersion = requestParts[2];

                if (!httpVersion.startsWith("HTTP/")) {
                    sendBadRequest(writer);
                    return;
                }

                String[] pathSegments = resourcePath.split("/");
                Deque<String> pathStack = new ArrayDeque<>();

                for (String segment : pathSegments) {
                    if (segment.equals(".") || segment.isEmpty()) {
                        continue;
                    }

                    if (segment.equals("..")) {
                        if (!pathStack.isEmpty()) {
                            pathStack.pop();
                        }
                    } else {
                        pathStack.push(segment);
                    }
                }

                StringBuilder rewrittenPath = new StringBuilder();
                for (Iterator<String> it = pathStack.descendingIterator(); it.hasNext();) {
                    rewrittenPath.append("/").append(it.next());
                }

                resourcePath = rewrittenPath.toString();

                if (resourcePath.isEmpty()) {
                    resourcePath = "/";
                }

                Map<String, String> headers = new HashMap<>();
                String headerLine;
                while (!(headerLine = reader.readLine()).isEmpty()) {
                    String[] headerParts = headerLine.split(": ");
                    if (headerParts.length == 2) {
                        headers.put(headerParts[0].trim(), headerParts[1].trim());
                        System.out.println("Request Header: " + headerParts[0].trim() + ": " + headerParts[1].trim());
                    }
                }

                if (!headers.containsKey("Host")) {
                    sendBadRequest(writer);
                    return;
                }

                boolean isChunked = "yes".equalsIgnoreCase(headers.get("chunked"));

                switch (method) {
                    case "GET":
                    case "HEAD":
                        try {
                            boolean headOnly = method.equals("HEAD");
                            serveResource(writer, clientSocket.getOutputStream(), resourcePath, headOnly, isChunked);
                        } catch (IOException e) {
                            sendInternalServerError(writer);
                            System.err.println("Error serving resource: " + e.getMessage());
                        }

                        break;
                    case "POST":
                        try {
                            Map<String, String> parameters = parseParameters(resourcePath);
                            Map<String, String> bodyParameters = new HashMap<>();

                            if (headers.containsKey("Content-Length")) {
                                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                                char[] body = new char[contentLength];
                                reader.read(body, 0, contentLength);
                                String requestBody = new String(body);
                                bodyParameters = parseParameters("?" + requestBody);
                                parameters.putAll(bodyParameters);

                            }

                            if ("/params_info.html".equals(resourcePath.split("\\?")[0])) {
                                storeParameters(parameters);
                                generateParamsInfoPage(storedParameters);
                                serveParamsInfoPage(writer, clientSocket.getOutputStream(), isChunked);
                            } else {
                                boolean isGoodRequest = serveResource(writer, clientSocket.getOutputStream(), resourcePath, false, isChunked);
                                if (isGoodRequest) {
                                    storeParameters(bodyParameters);
                                }
                            }
                        } catch (Exception e) {
                            sendInternalServerError(writer);
                            System.err.println("Error handling POST request: " + e.getMessage());
                        }
                        break;
                    case "TRACE":
                        StringBuilder traceResponseBuilder = new StringBuilder();

                        traceResponseBuilder.append(requestLine).append("\r\n");
                        for (Map.Entry<String, String> header : headers.entrySet()) {
                            traceResponseBuilder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                        }
                        traceResponseBuilder.append("\r\n");

                        byte[] traceResponseBody = traceResponseBuilder.toString().getBytes(StandardCharsets.UTF_8);

                        if (isChunked) {
                            writer.write("HTTP/1.1 200 OK\r\n");
                            writer.write("Transfer-Encoding: chunked\r\n");
                            writer.write("Content-Type: application/octet-stream\r\n\r\n");
                            writer.flush();

                            System.out.println("Response Header: HTTP/1.1 200 OK");
                            System.out.println("Response Header: Transfer-Encoding: chunked");
                            System.out.println("Response Header: Content-Type: application/octet-stream");

                            sendChunkedResponse(clientSocket.getOutputStream(), traceResponseBody);
                        } else {
                            writer.write("HTTP/1.1 200 OK\r\n");
                            writer.write("Content-Type: application/octet-stream\r\n");
                            writer.write("Content-Length: " + traceResponseBody.length + "\r\n\r\n");
                            writer.flush();

                            System.out.println("Response Header: HTTP/1.1 200 OK");
                            System.out.println("Response Header: Content-Type: application/octet-stream");
                            System.out.println("Response Header: Content-Length: " + traceResponseBody.length);

                            clientSocket.getOutputStream().write(traceResponseBody);
                            clientSocket.getOutputStream().flush();
                        }
                        break;
                    default:

                        sendNotImplemented(writer);
                        break;
                }

            } catch (IOException e) {
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
            String filePath = rootDirectory + "params_info.html";
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
                        .append("    <table border=\"1\">\n")
                        .append("        <tr>\n")
                        .append("            <th>Parameter Name</th>\n")
                        .append("            <th>Value</th>\n")
                        .append("        </tr>\n");

                for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                    for (String value : entry.getValue()) {
                        builder.append("        <tr>\n")
                                .append("            <td>").append(entry.getKey()).append("</td>\n")
                                .append("            <td>").append(value).append("</td>\n")
                                .append("        </tr>\n");
                    }
                }

                builder.append("    </table>\n")
                        .append("</body>\n")
                        .append("</html>");

                fileWriter.write(builder.toString());
            }
        }

        private void serveParamsInfoPage(BufferedWriter writer, OutputStream out, boolean isChunked) throws IOException {
            String filePath = rootDirectory + "params_info.html";
            Path path = Paths.get(filePath);

            if (Files.exists(path) && !Files.isDirectory(path)) {
                byte[] fileContent = Files.readAllBytes(path);
                String contentType = "text/html";

                if (isChunked) {
                    writer.write("HTTP/1.1 200 OK\r\n");
                    writer.write("Transfer-Encoding: chunked\r\n");
                    writer.write("Content-Type: text/html\r\n\r\n");
                    writer.flush();

                    System.out.println("Response Header: HTTP/1.1 200 OK");
                    System.out.println("Response Header: Transfer-Encoding: chunked");
                    System.out.println("Response Header: Content-Type: text/html");

                    sendChunkedResponse(clientSocket.getOutputStream(), fileContent);
                } else {
                    writer.write("HTTP/1.1 200 OK\r\n");
                    writer.write("Content-Type: " + contentType + "\r\n");
                    writer.write("Content-Length: " + fileContent.length + "\r\n");
                    writer.write("\r\n");
                    writer.flush();

                    System.out.println("HTTP/1.1 200 OK");
                    System.out.println("Content-Type: " + contentType);
                    System.out.println("Content-Length: " + fileContent.length);

                    out.write(fileContent);
                    out.flush();
                }

            } else {
                sendNotFound(writer);
            }
        }

        private boolean serveResource(BufferedWriter writer, OutputStream out, String resourcePath, boolean headOnly, boolean isChunked) throws IOException {
            String filePath = resourcePath;
            Map<String, String> requestParameters = new HashMap<>();
            boolean isGoodRequest = false;

            if (!headOnly && resourcePath.contains("?")) {
                int paramIndex = resourcePath.indexOf("?");
                String paramString = resourcePath.substring(paramIndex + 1);
                requestParameters = parseParameters("?" + paramString);
                filePath = resourcePath.substring(0, paramIndex);
            }

            Path path = Paths.get(rootDirectory).resolve(filePath.substring(1)).normalize();
            if (!path.startsWith(Paths.get(rootDirectory))) {
                sendBadRequest(writer);
                return isGoodRequest;
            }

            if (Files.isDirectory(path)) {
                path = path.resolve(defaultPage);
            }

            if (Files.exists(path) && !Files.isDirectory(path)) {
                String contentType = determineContentType(path);
                byte[] fileContent = Files.readAllBytes(path);
                storeParameters(requestParameters);

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

                System.out.println("Response Header: HTTP/1.1 200 OK");
                if (isChunked) {
                    System.out.println("Response Header: Transfer-Encoding: chunked");
                }
                System.out.println("Response Header: Content-Length: " + fileContent.length);
                System.out.println("Response Header: Content-Type: " + contentType);
                return true;

            } else {
                sendNotFound(writer);
                return false;
            }
        }

        private void sendChunkedResponse(OutputStream out, byte[] data) throws IOException {
            int offset = 0;
            while (offset < data.length) {
                int chunkSize = Math.min(4096, data.length - offset);
                String chunkHeader = Integer.toHexString(chunkSize) + "\r\n";
                out.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
                out.write(data, offset, chunkSize);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                offset += chunkSize;
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private String determineContentType(Path path) {
            String contentType = "application/octet-stream";
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

        private void sendBadRequest(BufferedWriter writer) {
            try {
                writer.write("HTTP/1.1 400 Bad Request\r\n\r\n");
                writer.flush();
                System.out.println("Response Header: HTTP/1.1 400 Bad Request");
            } catch (IOException e) {
                System.err.println("Error sending 400 Bad Request: " + e.getMessage());
            }
        }

        private void sendNotFound(BufferedWriter writer) {
            try {
                writer.write("HTTP/1.1 404 Not Found\r\n\r\n");
                writer.flush();
                System.out.println("Response Header: HTTP/1.1 404 Not Found");
            } catch (IOException e) {
                System.err.println("Error sending 404 Not Found: " + e.getMessage());
            }
        }

        private void sendNotImplemented(BufferedWriter writer) {
            try {
                writer.write("HTTP/1.1 501 Not Implemented\r\n\r\n");
                writer.flush();
                System.out.println("Response Header: HTTP/1.1 501 Not Implemented");
            } catch (IOException e) {
                System.err.println("Error sending 501 Not Implemented: " + e.getMessage());
            }
        }

        private void sendInternalServerError(BufferedWriter writer) {
            try {
                writer.write("HTTP/1.1 500 Internal Server Error\r\n\r\n");
                writer.flush();
                System.out.println("Response Header: HTTP/1.1 500 Internal Server Error");
            } catch (IOException e) {
                System.err.println("Error sending 500 Internal Server Error: " + e.getMessage());
            }
        }
    }
}
