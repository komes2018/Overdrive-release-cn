package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.Test;

public class VehicleCommandSerializationTest {

    @Test
    public void localCommandsRoundTripAcrossTheAppProcessBoundary() throws Exception {
        VehicleCommandRouter.SeatHeatCommand original =
                new VehicleCommandRouter.SeatHeatCommand(
                        2, 2, 0, 1, 2, 0, true, 1234L);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }

        Object decoded;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            decoded = input.readObject();
        }

        assertTrue(decoded instanceof VehicleCommandRouter.SeatHeatCommand);
        VehicleCommandRouter.SeatHeatCommand command =
                (VehicleCommandRouter.SeatHeatCommand) decoded;
        assertEquals(original.name(), command.name());
        assertEquals(2, command.position);
        assertEquals(2, command.level);
        assertEquals(2, command.passengerHeat);
    }
}
