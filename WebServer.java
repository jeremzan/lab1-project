import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer {
    public static void main(String[] args) {
        int port = 8080; // Default port number, can be changed to read from config.ini later
        int maxThreads = 10; // Maximum number of threads in the thread pool
        
        ExecutorService executor = Executors.newFixedThreadPool(maxThreads); // Create a thread pool

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from " + clientSocket);

                // Submit client connection handling to the thread pool
                executor.submit(() -> handleClient(clientSocket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdown(); // Shutdown the ExecutorService gracefully
        }
    }

    private static void handleClient(Socket clientSocket) {
        try {
            // Here we will read the client's request and send a basic HTTP response
            String httpResponse = "HTTP/1.1 200 OK\r\n\r\nHello World!";
            clientSocket.getOutputStream().write(httpResponse.getBytes("UTF-8"));
            clientSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
