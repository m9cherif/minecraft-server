package com.baarcha.arcade;

import org.bukkit.Location;

import java.util.List;

/**
 * Implemented by modes that place players at per-player spawn points instead
 * of a single shared spot (islands, team bases, duelling pedestals...).
 */
public interface SpawnProvider {

    /** One spawn per expected participant, cycled if the list is shorter. */
    List<Location> spawns();

    /** Where eliminated players watch from. */
    Location spectatorSpawn();
}
