import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
    public static void main(String[] args) {
        int port = 8080; // Default port number, can be changed to read from config.ini later

        // Create a thread pool using ExecutorService
        ExecutorService executorService = Executors.newFixedThreadPool(10); // You can adjust the pool size as needed

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from " + clientSocket);

                // Use the executorService to submit tasks (handleClient) instead of creating threads
                executorService.submit(() -> handleClient(clientSocket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Shutdown the executorService when done
            executorService.shutdown();
        }
    }

    private static void handleClient(Socket clientSocket) {
        try {
            // Here we will read the client's request and send a basic HTTP response
            // For now, it's just a placeholder to ensure the connection works
            String httpResponse = "HTTP/1.1 200 OK\r\n\r\nHello World!";
            clientSocket.getOutputStream().write(httpResponse.getBytes("UTF-8"));
            clientSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
