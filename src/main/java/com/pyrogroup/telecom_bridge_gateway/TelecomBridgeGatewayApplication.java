package com.pyrogroup.telecom_bridge_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TelecomBridgeGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(TelecomBridgeGatewayApplication.class, args);
	}

}
