package org.starexec.servlets;

import org.starexec.config.EnvironmentConfig;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Servlet that dynamically generates CSS properties based on environment configuration.
 * This replaces the build-time Maven property substitution.
 */
@WebServlet("/css/_properties.scss")
public class CssPropertiesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        
        response.setContentType("text/css");
        response.setCharacterEncoding("UTF-8");
        
        // Allow caching for 1 hour (3600 seconds)
        response.setHeader("Cache-Control", "public, max-age=3600");
        
        try (PrintWriter out = response.getWriter()) {
            // Generate CSS variables based on environment configuration
            out.println("/* Generated dynamically from environment variables */");
            out.println("$rootUrl: \"/" + EnvironmentConfig.getAppName() + "/\";");
            out.println("$webAddress: \"" + EnvironmentConfig.getWebAddress() + "\";");
            out.println("$proxyAddress: \"" + EnvironmentConfig.getProxyAddress() + "\";");
            out.println("$webBaseDirectory: \"" + EnvironmentConfig.getWebBaseDirectory() + "\";");
            // Add more CSS variables as needed
        }
    }
}
