package com.pyrogroup.telecom_bridge_gateway.protocol;

import java.net.InetAddress;

/** Who we are on the Diameter network (Origin-Host / Origin-Realm / Host-IP-Address). */
public record DiameterIdentity(String host, String realm, InetAddress ip, String productName) {
}
