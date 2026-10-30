package com.xrdavies.mob.ui.files

import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesEnabledTest {
    private val transfers = Health(ok = true, features = listOf("fileTransfers"), runners = null, runner = null, checks = null)
    private val paired = Machine("m1", "Office computer", "http://10.0.2.2:8080")

    @Test fun pairedMachineWithTransfersIsEnabled() {
        // Pairing leaves needsPairing null, not false.
        assertTrue(paired.needsPairing == null)
        assertTrue(filesEnabled(transfers, paired))
    }

    @Test fun disabledWithoutTransfersPairingOrMachine() {
        assertFalse(filesEnabled(transfers.copy(features = listOf("uploads")), paired))
        assertFalse(filesEnabled(null, paired))
        assertFalse(filesEnabled(transfers, paired.withNeedsPairing(true)))
        assertFalse(filesEnabled(transfers, null))
    }
}
