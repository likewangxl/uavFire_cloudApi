package com.dji.sample.manage.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @Value("${pilot2.web-entry:http://192.168.50.254:8080/pilot-login}")
    private String webEntry;

    @GetMapping("/")
    public ResponseEntity<Void> root() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, webEntry);
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }
}
