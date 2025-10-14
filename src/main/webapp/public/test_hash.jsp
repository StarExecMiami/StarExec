<%@ page import="java.security.MessageDigest" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%
    try {
        String password = "admin";
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        out.println("Password: " + password + "<br>");
        out.println("Hash: " + hexString.toString() + "<br>");
        out.println("Hash length: " + hexString.toString().length() + "<br>");
    } catch (Exception e) {
        out.println("Error: " + e.getMessage());
    }
%>
