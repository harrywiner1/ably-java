package io.ably.lib.util;

public class Platform {
	public static final String name = "java";

	public static NetworkConnectivity getNetworkConnectvity() {
		return JavaNetworkConnectivity.getInstance();
	}
}
