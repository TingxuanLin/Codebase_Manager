package com.codebasemanager;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	@GetMapping("/")
	public Map<String, Object> root() {
		return Map.of(
				"service", "Codebase_Manager",
				"status", "running",
				"timestamp", Instant.now().toString());
	}
}
