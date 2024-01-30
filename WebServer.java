import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
    private static String rootDirectory;
    private static String defaultPage;
    private static int maxThreads;

    public static void main(String[] args) {
        Properties config = loadConfig("config.ini");
        int port = Integer.parseInt(config.getProperty("port", "8080")); // Default to 8080 if not specified
        rootDirectory = config.getProperty("root", "~/www/lab/html/").replace("~", System.getProperty("user.home"));
        defaultPage = config.getProperty("defaultPage", "index.html");
        maxThreads = Integer.parseInt(config.getProperty("maxThreads", "10")); // Default to 10 if not specified

        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected from " + clientSocket.getInetAddress() + " on port " + clientSocket.getPort() + ".");
                executor.submit(() -> handleClient(clientSocket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }

    private static Properties loadConfig(String fileName) {
        Properties config = new Properties();
        try (FileInputStream fis = new FileInputStream(fileName)) {
            config.load(fis);
        } catch (IOException e) {
            System.err.println("Could not load config file, using default settings");
        }
        return config;
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {
            writer.write("Hello World from client!\r\n");
            String requestLine = reader.readLine();
    
            if (requestLine != null && !requestLine.isEmpty()) {
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length >= 3 && requestParts[0].equals("GET")) {
                    String filePath = requestParts[1].equals("/") ? defaultPage : requestParts[1];
                    serveFile(writer, clientSocket.getOutputStream(), filePath); // Pass clientSocket's OutputStream
                } else {
                    sendBadRequest(writer);
                }
            } else {
                sendBadRequest(writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    

    private static void serveFile(BufferedWriter writer, OutputStream out, String filePath) throws IOException {
        Path path = Paths.get(rootDirectory, filePath);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            writer.write("HTTP/1.1 200 OK\r\n");
            writer.write("Content-Length: " + Files.size(path) + "\r\n");
            writer.write("Content-Type: text/html\r\n"); // Simplification for example purposes
            writer.write("\r\n");
            writer.flush();
    
            Files.copy(path, out);
        } else {
            sendNotFound(writer);
        }
    }
    

    private static void sendBadRequest(BufferedWriter writer) throws IOException {
        writer.write("HTTP/1.1 400 Bad Request\r\n");
        writer.write("\r\n");
        writer.flush();
    }

    private static void sendNotFound(BufferedWriter writer) throws IOException {
        writer.write("HTTP/1.1 404 Not Found\r\n");
        writer.write("\r\n");
        writer.flush();
    }
}
