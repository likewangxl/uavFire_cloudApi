package com.yx.uavfire.msdk.service;

import com.yx.uavfire.msdk.model.MsdkCommandDTO;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class InMemoryMsdkCommandStore implements MsdkCommandStore {

    private final ConcurrentHashMap<String, MsdkCommandDTO> commands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Queue<String>> queues = new ConcurrentHashMap<>();

    @Override
    public void save(MsdkCommandDTO command) {
        commands.put(command.getCommandId(), command);
    }

    @Override
    public void enqueue(String aircraftSn, String commandId) {
        queues.computeIfAbsent(aircraftSn, key -> new ConcurrentLinkedQueue<>()).add(commandId);
    }

    @Override
    public Optional<String> dequeue(String aircraftSn) {
        Queue<String> queue = queues.get(aircraftSn);
        if (queue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queue.poll());
    }

    @Override
    public Optional<MsdkCommandDTO> find(String commandId) {
        return Optional.ofNullable(commands.get(commandId));
    }
}
