import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StudentExpenseTrackerServer {

    static class Expense {
        String description;
        String category;
        double amount;
        String date;

        Expense(String description, String category, double amount) {
            this.description = description;
            this.category = category;
            this.amount = amount;
            this.date = LocalDate.now().toString();
        }
    }

    static class Saving {
        String description;
        double amount;
        String date;

        Saving(String description, double amount) {
            this(description, amount, LocalDate.now().toString());
        }

        Saving(String description, double amount, String date) {
            this.description = description;
            this.amount = amount;
            this.date = date;
        }
    }

    static final ArrayList<Expense> expenses = new ArrayList<>();
    static final ArrayList<Saving> savings = new ArrayList<>();

    static double monthlyBudget = 0.0;
    static final Path SAVINGS_FILE = Path.of("savings.txt");

    // Login details - change these to whatever you like.
    static final String LOGIN_USERNAME = "student";
    static final String LOGIN_PASSWORD = "student123";

    // Tokens of everyone who is currently logged in.
    static final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws IOException {
        loadSavings();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("localhost", 8080), 0);

        // Main dashboard
        server.createContext("/", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                send(exchange, 404, "Page not found.",
                        "text/plain; charset=UTF-8");
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                send(exchange, 405, "Method not allowed.",
                        "text/plain; charset=UTF-8");
                return;
            }

            if (!isLoggedIn(exchange)) {
                redirectTo(exchange, "/login");
                return;
            }

            try {
                String html = Files.readString(
                        Path.of("index.html"), StandardCharsets.UTF_8);

                double totalExpenses = 0.0;
                for (Expense e : expenses) {
                    totalExpenses += e.amount;
                }

                double totalSavings = getTotalSavings();
                double remainingBudget = monthlyBudget - totalExpenses;

                double percentUsed = monthlyBudget > 0
                        ? Math.min(100.0,
                            totalExpenses / monthlyBudget * 100.0)
                        : 0.0;

                String budgetMessage;
                if (monthlyBudget <= 0) {
                    budgetMessage = "Set your monthly budget to start tracking.";
                } else if (remainingBudget < 0) {
                    budgetMessage = "You have exceeded your monthly budget.";
                } else {
                    budgetMessage = "You are within your monthly budget.";
                }

                StringBuilder expenseRows = new StringBuilder();

                for (int i = expenses.size() - 1; i >= 0; i--) {
                    Expense e = expenses.get(i);

                    expenseRows.append("<tr>")
                            .append("<td>").append(escape(e.date)).append("</td>")
                            .append("<td>").append(escape(e.description)).append("</td>")
                            .append("<td><span class=\"category-tag\">")
                            .append(escape(e.category))
                            .append("</span></td>")
                            .append("<td class=\"money-cell\">₹")
                            .append(money(e.amount)).append("</td>")
                            .append("</tr>");
                }

                if (expenses.isEmpty()) {
                    expenseRows.append(
                            "<tr><td colspan=\"4\" class=\"empty-state\">")
                            .append("No expenses added yet.</td></tr>");
                }

                StringBuilder savingRows = new StringBuilder();

                for (int i = savings.size() - 1; i >= 0; i--) {
                    Saving s = savings.get(i);

                    savingRows.append("<tr>")
                            .append("<td>").append(escape(s.date)).append("</td>")
                            .append("<td>").append(escape(s.description)).append("</td>")
                            .append("<td class=\"money-cell savings-money\">₹")
                            .append(money(s.amount)).append("</td>")
                            .append("</tr>");
                }

                if (savings.isEmpty()) {
                    savingRows.append(
                            "<tr><td colspan=\"3\" class=\"empty-state\">")
                            .append("No savings added yet.</td></tr>");
                }

                html = html.replace("BUDGET_AMOUNT", money(monthlyBudget));
                html = html.replace("TOTAL_AMOUNT", money(totalExpenses));
                html = html.replace("REMAINING_AMOUNT", money(remainingBudget));
                html = html.replace("SAVINGS_AMOUNT", money(totalSavings));
                html = html.replace("PERCENT_USED", money(percentUsed));
                html = html.replace("PROGRESS_WIDTH", money(percentUsed));
                html = html.replace("BUDGET_MESSAGE", escape(budgetMessage));
                html = html.replace("EXPENSE_ROWS", expenseRows.toString());
                html = html.replace("SAVINGS_ROWS", savingRows.toString());

                send(exchange, 200, html, "text/html; charset=UTF-8");

            } catch (IOException e) {
                send(exchange, 500,
                        "Could not load index.html. Check your project folder.",
                        "text/plain; charset=UTF-8");
            }
        });

        // CSS file
        server.createContext("/style.css", exchange -> {
            try {
                String css = Files.readString(
                        Path.of("style.css"), StandardCharsets.UTF_8);
                send(exchange, 200, css, "text/css; charset=UTF-8");
            } catch (IOException e) {
                send(exchange, 500, "Could not load style.css.",
                        "text/plain; charset=UTF-8");
            }
        });

        // Save monthly budget
        server.createContext("/setBudget", exchange -> {
            if (!isLoggedIn(exchange)) {
                redirectTo(exchange, "/login");
                return;
            }

            if (!isPost(exchange)) {
                redirect(exchange);
                return;
            }

            try {
                Map<String, String> form = readForm(exchange);
                double amount = Double.parseDouble(
                        form.getOrDefault("budget", ""));

                if (!Double.isFinite(amount) || amount < 0) {
                    send(exchange, 400, "Enter a valid budget amount.",
                            "text/plain; charset=UTF-8");
                    return;
                }

                monthlyBudget = amount;
                redirect(exchange);

            } catch (NumberFormatException e) {
                send(exchange, 400, "Invalid budget amount.",
                        "text/plain; charset=UTF-8");
            } catch (IOException e) {
                send(exchange, 500, "Could not save the budget.",
                        "text/plain; charset=UTF-8");
            }
        });

        // Add an expense
        server.createContext("/addExpense", exchange -> {
            if (!isLoggedIn(exchange)) {
                redirectTo(exchange, "/login");
                return;
            }

            if (!isPost(exchange)) {
                redirect(exchange);
                return;
            }

            try {
                Map<String, String> form = readForm(exchange);

                String description = form.getOrDefault("description", "").trim();
                String category = form.getOrDefault("category", "").trim();
                double amount = Double.parseDouble(
                        form.getOrDefault("amount", ""));

                if (description.isEmpty() || category.isEmpty()
                        || !Double.isFinite(amount) || amount <= 0) {
                    send(exchange, 400,
                            "Enter an expense description, category, and valid amount.",
                            "text/plain; charset=UTF-8");
                    return;
                }

                expenses.add(new Expense(description, category, amount));
                redirect(exchange);

            } catch (NumberFormatException e) {
                send(exchange, 400, "Invalid expense amount.",
                        "text/plain; charset=UTF-8");
            } catch (IOException e) {
                send(exchange, 500, "Could not add the expense.",
                        "text/plain; charset=UTF-8");
            }
        });

        // Add savings WITHOUT navigating to another page.
        // The browser receives JSON and updates the dashboard using JavaScript.
        server.createContext("/addSaving", exchange -> {
            if (!isLoggedIn(exchange)) {
                send(exchange, 401, "Please log in again.",
                        "text/plain; charset=UTF-8");
                return;
            }

            if (!isPost(exchange)) {
                send(exchange, 405, "Please submit the savings form.",
                        "text/plain; charset=UTF-8");
                return;
            }

            try {
                Map<String, String> form = readForm(exchange);
                String amountText = form.getOrDefault("amount", "").trim();

                if (amountText.isEmpty()) {
                    send(exchange, 400, "Please enter a savings amount.",
                            "text/plain; charset=UTF-8");
                    return;
                }

                double amount = Double.parseDouble(amountText);

                if (!Double.isFinite(amount) || amount <= 0) {
                    send(exchange, 400, "Amount must be greater than zero.",
                            "text/plain; charset=UTF-8");
                    return;
                }

                Saving saving = new Saving("Savings deposit", amount);
                savings.add(saving);

                try {
                    saveSavings();
                } catch (IOException e) {
                    savings.remove(saving);
                    throw e;
                }

                String response = "{\"success\":true,"
                        + "\"amount\":" + money(amount) + ","
                        + "\"total\":" + money(getTotalSavings()) + ","
                        + "\"date\":\"" + saving.date + "\","
                        + "\"description\":\"Savings deposit\"}";

                send(exchange, 200, response, "application/json; charset=UTF-8");

            } catch (NumberFormatException e) {
                send(exchange, 400, "Please enter a valid savings amount.",
                        "text/plain; charset=UTF-8");
            } catch (IOException e) {
                send(exchange, 500, "Could not write savings.txt.",
                        "text/plain; charset=UTF-8");
            }
        });

        // Login page + login check
        server.createContext("/login", exchange -> {
            String method = exchange.getRequestMethod();

            if (method.equalsIgnoreCase("POST")) {
                try {
                    Map<String, String> form = readForm(exchange);
                    String username = form.getOrDefault("username", "").trim();
                    String password = form.getOrDefault("password", "");

                    if (credentialsMatch(username, password)) {
                        String token = UUID.randomUUID().toString();
                        sessions.add(token);
                        exchange.getResponseHeaders().add("Set-Cookie",
                                "SESSION=" + token
                                + "; Path=/; HttpOnly; SameSite=Lax");
                        redirect(exchange);
                    } else {
                        redirectTo(exchange, "/login?error=1");
                    }
                } catch (IOException e) {
                    send(exchange, 500, "Could not process the login.",
                            "text/plain; charset=UTF-8");
                }
                return;
            }

            if (!method.equalsIgnoreCase("GET")) {
                send(exchange, 405, "Method not allowed.",
                        "text/plain; charset=UTF-8");
                return;
            }

            if (isLoggedIn(exchange)) {
                redirect(exchange);
                return;
            }

            try {
                String html = Files.readString(
                        Path.of("login.html"), StandardCharsets.UTF_8);

                boolean failed = "error=1".equals(
                        exchange.getRequestURI().getQuery());

                html = html.replace("LOGIN_MESSAGE", failed
                        ? "<p class=\"login-error\" role=\"alert\">"
                          + "Incorrect username or password.</p>"
                        : "");

                send(exchange, 200, html, "text/html; charset=UTF-8");

            } catch (IOException e) {
                send(exchange, 500,
                        "Could not load login.html. Check your project folder.",
                        "text/plain; charset=UTF-8");
            }
        });

        // Logout
        server.createContext("/logout", exchange -> {
            String token = getSessionToken(exchange);
            if (token != null) {
                sessions.remove(token);
            }

            exchange.getResponseHeaders().add("Set-Cookie",
                    "SESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
            redirectTo(exchange, "/login");
        });

        server.setExecutor(null);
        server.start();

        System.out.println("Student Expense Tracker is running.");
        System.out.println("Open: http://localhost:8080/");
        System.out.println("Login: " + LOGIN_USERNAME + " / " + LOGIN_PASSWORD);
        System.out.println("Savings file: " + SAVINGS_FILE.toAbsolutePath());
        System.out.println("Keep this terminal open.");
    }

    static String getSessionToken(HttpExchange exchange) {
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");

        if (cookies == null) {
            return null;
        }

        for (String cookie : cookies.split(";")) {
            String[] parts = cookie.trim().split("=", 2);

            if (parts.length == 2 && parts[0].equals("SESSION")) {
                return parts[1];
            }
        }

        return null;
    }

    static boolean isLoggedIn(HttpExchange exchange) {
        String token = getSessionToken(exchange);
        return token != null && sessions.contains(token);
    }

    static boolean credentialsMatch(String username, String password) {
        boolean userOk = MessageDigest.isEqual(
                username.getBytes(StandardCharsets.UTF_8),
                LOGIN_USERNAME.getBytes(StandardCharsets.UTF_8));

        boolean passOk = MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8),
                LOGIN_PASSWORD.getBytes(StandardCharsets.UTF_8));

        return userOk && passOk;
    }

    static void redirectTo(HttpExchange exchange, String location)
            throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    static boolean isPost(HttpExchange exchange) {
        return exchange.getRequestMethod().equalsIgnoreCase("POST");
    }

    static double getTotalSavings() {
        double total = 0.0;

        for (Saving saving : savings) {
            total += saving.amount;
        }

        return total;
    }

    static Map<String, String> readForm(HttpExchange exchange)
            throws IOException {
        String body = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);

        Map<String, String> form = new HashMap<>();

        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }

            String[] parts = pair.split("=", 2);

            String key = URLDecoder.decode(
                    parts[0], StandardCharsets.UTF_8);

            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";

            form.put(key, value);
        }

        return form;
    }

    static void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    static void send(HttpExchange exchange, int status, String response,
                     String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);

        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    static void loadSavings() throws IOException {
        savings.clear();

        if (!Files.exists(SAVINGS_FILE)) {
            return;
        }

        for (String line : Files.readAllLines(
                SAVINGS_FILE, StandardCharsets.UTF_8)) {
            try {
                String[] parts = line.split("\\|", -1);

                if (parts.length != 3) {
                    continue;
                }

                String description = new String(
                        Base64.getDecoder().decode(parts[0]),
                        StandardCharsets.UTF_8);

                double amount = Double.parseDouble(parts[1]);
                LocalDate.parse(parts[2]);

                if (Double.isFinite(amount) && amount > 0) {
                    savings.add(new Saving(description, amount, parts[2]));
                }
            } catch (IllegalArgumentException ignored) {
                System.err.println("Skipped an invalid line in savings.txt.");
            }
        }
    }

    static void saveSavings() throws IOException {
        Path absoluteFile = SAVINGS_FILE.toAbsolutePath();
        Path parent = absoluteFile.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (var writer = Files.newBufferedWriter(
                absoluteFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {

            for (Saving saving : savings) {
                String encodedDescription = Base64.getEncoder()
                        .encodeToString(
                                saving.description.getBytes(StandardCharsets.UTF_8));

                writer.write(encodedDescription + "|"
                        + money(saving.amount) + "|"
                        + saving.date);
                writer.newLine();
            }
        }
    }

    static String money(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}