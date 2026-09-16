package com.endpointposture.endpoint;

import com.endpointposture.endpoint.dto.EndpointResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    @GetMapping
    public List<EndpointResponse> listAll() {
        return service.listAll();
    }

    @GetMapping("/{id}")
    public EndpointResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }
}