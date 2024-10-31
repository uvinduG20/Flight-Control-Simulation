package edu.curtin.saed.assignment1;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class App extends Application
{
    private static final int NUM_AIRPORTS = 5;
    private static final int PLANES_PER_AIRPORT = 3;
    private static final int GRID_WIDTH = 10;
    private static final int GRID_HEIGHT = 10;
    private static final double PLANE_SPEED = 0.1;

    private GridArea area;
    private List<Airport> airports;
    private List<Plane> planes;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private boolean running;
    private TextArea textArea;
    private Label statusLabel;
    private AtomicInteger inFlight = new AtomicInteger(0);
    private AtomicInteger undergoingService = new AtomicInteger(0);
    private AtomicInteger completedTrips = new AtomicInteger(0);

    private final BlockingQueue<FlightRequest> flightRequestQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<ServiceRequest> serviceRequestQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args)
    {
        launch();
    }

    @Override
    public void start(Stage stage)
    {
        area = new GridArea(GRID_WIDTH, GRID_HEIGHT);
        area.setStyle("-fx-background-color: #006000;");
        airports = new ArrayList<>();
        planes = new ArrayList<>();
        running = false;

        initializeAirportsAndPlanes();
        displayAirportsAndPlanes();

        var startBtn = new Button("Start");
        var endBtn = new Button("End");

        startBtn.setOnAction(event -> {
            if (!running) {
                running = true;
                startSimulation();
            }
        });

        endBtn.setOnAction(event -> {
            if (running) {
                running = false;
                stopSimulation();
            }
        });

        var statusText = new Label("Status");
        statusLabel = new Label("In-Flight: 0 | Service: 0 | Completed Trips: 0");

        textArea = new TextArea();
        textArea.setEditable(false);
        textArea.appendText("Sidebar Text\n");

        var toolbar = new ToolBar();
        toolbar.getItems().addAll(startBtn, endBtn, new Separator(), statusText, new Separator(), statusLabel);

        var splitPane = new SplitPane();
        splitPane.getItems().addAll(area, textArea);
        splitPane.setDividerPositions(0.75);

        var contentPane = new BorderPane();
        contentPane.setTop(toolbar);
        contentPane.setCenter(splitPane);

        var scene = new Scene(contentPane, 1200, 1000);
        stage.setScene(scene);
        stage.setTitle("Air Traffic Simulator");
        stage.show();
    }

    private void initializeAirportsAndPlanes()
    {
        Random random = new Random();

        for (int i = 0; i < NUM_AIRPORTS; i++) {
            int x = random.nextInt(GRID_WIDTH);
            int y = random.nextInt(GRID_HEIGHT);
            Airport airport = new Airport(i + 1, x, y);
            airports.add(airport);

            for (int j = 0; j < PLANES_PER_AIRPORT; j++) {
                int planeID = (i * PLANES_PER_AIRPORT) + j + 1;
                Plane plane = new Plane(planeID, airport, x, y);
                planes.add(plane);
            }
        }
    }

    private void displayAirportsAndPlanes()
    {
        for (Airport airport : airports) {
            GridAreaIcon airportIcon = new GridAreaIcon(
                    airport.getX(),
                    airport.getY(),
                    0.0,
                    1.0,
                    App.class.getClassLoader().getResourceAsStream("airport.png"),
                    "Airport " + airport.getId());
            area.getIcons().add(airportIcon);

            for (Plane plane : planes) {
                if (plane.getAirport().equals(airport)) {
                    GridAreaIcon planeIcon = new GridAreaIcon(
                            airport.getX(),
                            airport.getY(),
                            45.0,
                            1.0,
                            App.class.getClassLoader().getResourceAsStream("plane.png"),
                            "Plane " + plane.getId());
                    plane.setIcon(planeIcon);
                    area.getIcons().add(planeIcon);
                }
            }
        }

        area.requestLayout();
    }

    private void startSimulation()
    {
        executorService = Executors.newCachedThreadPool();
        scheduledExecutorService = Executors.newScheduledThreadPool(1);

        executorService.submit(() -> {
            while (running) {
                try {
                    List<FlightRequest> requestsBatch = new ArrayList<>();
                    flightRequestQueue.drainTo(requestsBatch, 5);

                    if (!requestsBatch.isEmpty()) {
                        for (FlightRequest request : requestsBatch) {
                            executorService.submit(() -> processFlightRequest(request));
                        }
                        updateSidebar("Executed a batch of " + requestsBatch.size() + " flight requests.");
                    }

                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        executorService.submit(() -> {
            while (running) {
                try {
                    ServiceRequest serviceRequest = serviceRequestQueue.take();
                    updateSidebar("Servicing plane " + serviceRequest.getPlane().getId() + " at Airport " + serviceRequest.getAirport().getId());
                    servicePlane(serviceRequest.getAirport(), serviceRequest.getPlane());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        for (Airport airport : airports) {
            executorService.submit(() -> {
                try {
                    Process proc;
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        proc = Runtime.getRuntime().exec(new String[]{"saed_flight_requests.bat", String.valueOf(NUM_AIRPORTS), String.valueOf(airport.getId() - 1)});
                    } else {
                        proc = Runtime.getRuntime().exec(new String[]{"saed_flight_requests", String.valueOf(NUM_AIRPORTS), String.valueOf(airport.getId() - 1)});
                    }

                    if (proc != null) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null && running) {
                                int destinationId = Integer.parseInt(line);
                                updateSidebar("Flight request from Airport " + airport.getId() + " to Airport " + destinationId);
                                flightRequestQueue.put(new FlightRequest(airport, destinationId));
                            }
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    updateSidebar("Error running saed_flight_requests: " + e.getMessage());
                }
            });
        }

        scheduledExecutorService.scheduleAtFixedRate(() -> {
            if (!running) {
                scheduledExecutorService.shutdown();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void stopSimulation()
    {
        running = false;
        if (executorService != null) {
            executorService.shutdownNow();  // Try to stop all actively executing tasks

            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("ExecutorService did not terminate");
                    // Retry to ensure termination
                    executorService.shutdownNow();
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        System.err.println("ExecutorService failed to terminate");
                    }
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();

            try {
                if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("ScheduledExecutorService did not terminate");
                    // Retry to ensure termination
                    scheduledExecutorService.shutdownNow();
                    if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        System.err.println("ScheduledExecutorService failed to terminate");
                    }
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processFlightRequest(FlightRequest request)
    {
        Plane availablePlane = findAvailablePlane(request.getOrigin());
        if (availablePlane != null) {
            Airport destinationAirport = airports.stream()
                    .filter(a -> a.getId() == request.getDestinationId())
                    .findFirst()
                    .orElse(null);
            if (destinationAirport != null) {
                movePlane(availablePlane, destinationAirport.getX(), destinationAirport.getY());
                updateSidebar("Plane " + availablePlane.getId() + " is flying from " + request.getOrigin().getId() + " to " + destinationAirport.getId());
                try {
                    serviceRequestQueue.put(new ServiceRequest(destinationAirport, availablePlane));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void movePlane(Plane plane, double targetX, double targetY)
    {
        double deltaX = targetX - plane.getX();
        double deltaY = targetY - plane.getY();
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        double steps = distance / PLANE_SPEED;

        inFlight.incrementAndGet();
        updateStatistics();

        for (int i = 0; i < steps; i++) {
            if (!running) {
                return;
            }
            double newX = plane.getX() + (deltaX / steps);
            double newY = plane.getY() + (deltaY / steps);
            plane.setPosition(newX, newY);

            Platform.runLater(() -> {
                plane.getIcon().setPosition(newX, newY);
                area.requestLayout();
            });

            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        inFlight.decrementAndGet();
        completedTrips.incrementAndGet();  // Ensure completed trips are incremented
        updateStatistics();

        plane.setInFlight(false);
        updateSidebar("Plane " + plane.getId() + " landed.");
    }

    private void servicePlane(Airport airport, Plane plane)
    {
        executorService.submit(() -> {
            try {
                Process proc;
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    proc = Runtime.getRuntime().exec(new String[]{"saed_plane_service.bat", String.valueOf(airport.getId()), String.valueOf(plane.getId())});
                } else {
                    proc = Runtime.getRuntime().exec(new String[]{"saed_plane_service", String.valueOf(airport.getId()), String.valueOf(plane.getId())});
                }

                undergoingService.incrementAndGet();
                updateStatistics();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String serviceMessage = reader.readLine();
                    updateSidebar("Service Completed: " + serviceMessage);
                }
            } catch (IOException e) {
                updateSidebar("Error running saed_plane_service: " + e.getMessage());
            } finally {
                undergoingService.decrementAndGet();
                updateStatistics();
            }
        });
    }

    private Plane findAvailablePlane(Airport airport)
    {
        synchronized (planes) {
            for (Plane plane : planes) {
                if (plane.getAirport().equals(airport) && plane.isAvailable()) {
                    plane.setInFlight(true);
                    return plane;
                }
            }
        }
        return null;
    }

    private void updateSidebar(String message)
    {
        Platform.runLater(() -> {
            textArea.appendText(message + "\n");
        });
    }

    private void updateStatistics()
    {
        Platform.runLater(() -> {
            statusLabel.setText("In-Flight: " + inFlight.get() + " | Service: " + undergoingService.get() + " | Completed Trips: " + completedTrips.get());
        });
    }

    private static class FlightRequest
    {
        private final Airport origin;
        private final int destinationId;

        public FlightRequest(Airport origin, int destinationId)
        {
            this.origin = origin;
            this.destinationId = destinationId;
        }

        public Airport getOrigin()
        {
            return origin;
        }

        public int getDestinationId()
        {
            return destinationId;
        }
    }

    private static class ServiceRequest
    {
        private final Airport airport;
        private final Plane plane;

        public ServiceRequest(Airport airport, Plane plane)
        {
            this.airport = airport;
            this.plane = plane;
        }

        public Airport getAirport()
        {
            return airport;
        }

        public Plane getPlane()
        {
            return plane;
        }
    }

    private class Airport
    {
        private final int id;
        private final int x, y;

        public Airport(int id, int x, int y)
        {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        public int getId()
        {
            return id;
        }

        public int getX()
        {
            return x;
        }

        public int getY()
        {
            return y;
        }
    }

    private class Plane
    {
        private final int id;
        private final Airport airport;
        private double x, y;
        private GridAreaIcon icon;
        private boolean inFlight;

        public Plane(int id, Airport airport, double x, double y)
        {
            this.id = id;
            this.airport = airport;
            this.x = x;
            this.y = y;
            this.inFlight = false;
        }

        public int getId()
        {
            return id;
        }

        public Airport getAirport()
        {
            return airport;
        }

        public double getX()
        {
            return x;
        }

        public double getY()
        {
            return y;
        }

        public GridAreaIcon getIcon()
        {
            return icon;
        }

        public void setIcon(GridAreaIcon icon)
        {
            this.icon = icon;
        }

        public void setPosition(double x, double y)
        {
            this.x = x;
            this.y = y;
        }

        public boolean isAvailable()
        {
            return !inFlight;
        }

        public void setInFlight(boolean inFlight)
        {
            this.inFlight = inFlight;
        }
    }
}
