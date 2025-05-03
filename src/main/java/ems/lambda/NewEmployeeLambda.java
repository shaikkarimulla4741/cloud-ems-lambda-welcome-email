package ems.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.*;
import com.google.gson.Gson;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class NewEmployeeLambda implements RequestHandler<Object, String> {

    private static final AWSSecretsManager secretsManager = AWSSecretsManagerClientBuilder.defaultClient();

    private static final String SECRET_NAME = System.getenv("SECRET_NAME");
    private static final String SENDER_EMAIL = System.getenv("SENDER_EMAIL");    

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    @Override
    public String handleRequest(Object input, Context context) {
        List<String> messages = new ArrayList<>();
        try {
            Map<String, String> credentials = getRDSCredentials(context);
            Class.forName("com.mysql.cj.jdbc.Driver");

            String dbUrl = String.format("jdbc:mysql://%s:3306/%s",
                    credentials.get("host"),
                    credentials.get("dbname"));

            try (Connection connection = DriverManager.getConnection(
                    dbUrl,
                    credentials.get("username"),
                    credentials.get("password"))) {

                messages.addAll(checkNewEmployees(connection, context));
            }
        } catch (Exception e) {
            logError(context, "New employee processing failed", e);
            return "Failed to process new employees: " + e.getMessage();
        }
        return "Successfully processed new employees";
    }

    /**
     * Fetches RDS credentials from AWS Secrets Manager.
     */
    private Map<String, String> getRDSCredentials(Context context) {
        try {
            GetSecretValueResult result = secretsManager.getSecretValue(
                    new GetSecretValueRequest().withSecretId(SECRET_NAME));
            return new Gson().fromJson(result.getSecretString(), Map.class);
        } catch (Exception e) {
            logError(context, "Failed to retrieve secrets", e);
            throw new RuntimeException("Secrets retrieval failed", e);
        }
    }

    private List<String> checkNewEmployees(Connection connection, Context context) throws SQLException {
        List<String> messages = new ArrayList<>();
        String query = "SELECT name, email, temp_password, user_email FROM employees " +
                "WHERE login_sent = false AND user_email IS NOT NULL";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String userEmail = rs.getString("user_email");
                if (!isValidEmail(userEmail)) {
                    logWarning(context, "Invalid user email skipped: " + userEmail);
                    continue;
                }

                String email = rs.getString("email");
                String messageBody = buildWelcomeMessage(
                        rs.getString("name"),
                        email,
                        rs.getString("temp_password")
                );

                sendEmail(userEmail, "Welcome to the Team!", messageBody, context);
                updateLoginSentStatus(connection, email);
                messages.add("Welcome sent to: " + userEmail);
            }
        }
        return messages;
    }

    /**
     * Sends an email using AWS SES.
     */
    private void sendEmail(String recipientEmail, String subject, String body, Context context) {
        try {
            if (!isValidEmail(recipientEmail)) {
                throw new IllegalArgumentException("Invalid recipient email: " + recipientEmail);
            }

            AmazonSimpleEmailService ses = AmazonSimpleEmailServiceClientBuilder.defaultClient();
            ses.sendEmail(new SendEmailRequest()
                    .withSource(SENDER_EMAIL)
                    .withDestination(new Destination().withToAddresses(recipientEmail))
                    .withMessage(new Message()
                            .withSubject(new Content().withCharset("UTF-8").withData(subject))
                            .withBody(new Body().withText(new Content().withCharset("UTF-8").withData(body)))));

            context.getLogger().log("Email sent to: " + recipientEmail);
        } catch (Exception e) {
            logError(context, "Email failed to " + recipientEmail, e);
            throw new RuntimeException("Email send failed", e);
        }
    }

    /**
     * Builds the welcome message for new employees.
     */
    private String buildWelcomeMessage(String name, String email, String tempPassword) {
        return String.format(
                "Dear %s,%n%nWelcome to the team! 🎉%n%n" +
                        "Your login email: %s%n" +
                        "Temporary password: %s%n%n" +
                        "Login at: localhost:5173%n%n" +
                        "Thank you!",
                name, email, tempPassword
        );
    }

    /**
     * Updates `login_sent` = true for a new employee after email is sent.
     */
    private void updateLoginSentStatus(Connection connection, String email) throws SQLException {
        String query = "UPDATE employees SET login_sent = true WHERE email = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, email);
            stmt.executeUpdate();
        }
    }


    /**
     * Validates an email address using regex.
     */
    private boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Logs error messages in the Lambda context.
     */
    private void logError(Context context, String message, Exception e) {
        context.getLogger().log("ERROR: " + message + " - " + e.getMessage());
    }

    /**
     * Logs warnings (e.g., invalid email addresses).
     */
    private void logWarning(Context context, String message) {
        context.getLogger().log("WARNING: " + message);
    }

}