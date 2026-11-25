package app.oriel.core

import org.junit.jupiter.api.Assumptions.assumeTrue

internal fun assumeLive() = assumeTrue(System.getProperty("oriel.live") == "true", "live run (-Plive=true)")

internal fun assumeProbe() = assumeTrue(System.getProperty("oriel.probe") == "true", "probe run (-Pprobe=true)")
