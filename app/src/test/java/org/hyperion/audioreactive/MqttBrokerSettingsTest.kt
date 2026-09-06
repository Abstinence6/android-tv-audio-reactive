package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttBrokerSettingsTest {
    @Test fun validatesOnlyLiteralPrivateRfc1918Ipv4AndValidPorts() {
        assertEquals(MqttBrokerSettings("192.168.1.1", 1883, "", ""), MqttBrokerSettings.fromInput("192.168.1.1", "1883", "", "").settings)
        listOf("192.168.1.1/24", "tcp://192.168.1.1", "broker.local", "127.0.0.1", "169.254.1.1", "172.15.0.1", "172.32.0.1", "192.167.1.1", "192.169.1.1", "8.8.8.8").forEach {
            assertNull("$it must be rejected", MqttBrokerSettings.fromInput(it, "1883", "", "").settings)
        }
        listOf("0", "65536", "x", "1883/path").forEach {
            assertNull("$it must be rejected", MqttBrokerSettings.fromInput("10.0.0.1", it, "", "").settings)
        }
    }

    @Test fun persistsBrokerStateWhileKeepingPasswordOutOfPublicState() {
        val broker = MqttBrokerSettings("10.2.3.4", 2883, "tv", "top-secret")
        val restored = AudioSettings.defaults().copy(mqttBroker = broker)
        assertEquals(broker, restored.mqttBroker)
        assertEquals("10.2.3.4:2883", broker.publicState())
        assertFalse(broker.publicState().contains("top-secret"))
        assertFalse(MqttContract.snapshot(restored, false, "idle").any { it.payload.contains("top-secret") })
    }

    @Test fun buildsOnlyValidatedTcpUriAndSetsCredentialsOnlyWhenUsernameExists() {
        val anonymous = MqttBrokerSettings.defaults()
        assertEquals("tcp://192.168.1.1:1883", MqttClientPolicy.uri(anonymous))
        assertNull(MqttClientPolicy.options(anonymous).username)
        assertNull(MqttClientPolicy.options(anonymous).password)

        val authenticated = MqttBrokerSettings("172.16.1.10", 1884, "tv", "secret")
        assertEquals("tcp://172.16.1.10:1884", MqttClientPolicy.uri(authenticated))
        assertEquals("tv", MqttClientPolicy.options(authenticated).username)
        assertEquals("secret", MqttClientPolicy.options(authenticated).password?.concatToString())
        assertFalse(MqttClientPolicy.validUri("tcp://8.8.8.8:1883"))
        assertFalse(MqttClientPolicy.validUri("ssl://192.168.1.1:1883"))
    }
}