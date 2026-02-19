package com.example;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppTest {
    @Test
    public void testGetMessage() {
        assertEquals("Hello from CI/CD Pipeline!", App.getMessage());
    }
}
