package com.rtsbuilding.rtsbuilding.network.feedback;

import com.rtsbuilding.rtsbuilding.network.RtsClientPayloadBridge;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers lightweight server-to-client RTS feedback packets.
 *
 * Feedback payloads are S2C-only and should still route through
 * RtsClientPayloadBridge to preserve dedicated-server classloading safety.
 */
public final class RtsFeedbackPackets {
    private RtsFeedbackPackets() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                S2CRtsDamageFeedbackPayload.TYPE,
                S2CRtsDamageFeedbackPayload.STREAM_CODEC,
                RtsClientPayloadBridge::handleDamageFeedback);
    }
}
