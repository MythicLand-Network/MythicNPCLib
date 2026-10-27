package cn.mythicland.npclib.bot;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

public class BotTargetSelector {
    public interface Settings {
        double searchDistance();
    }

    public interface Targets {
        Collection<? extends Player> bots();

        double healthRatio(Player player);
    }

    private final Targets targets;
    private final RandomGenerator random;
    private final Map<Cell, List<Player>> spatial = new HashMap<>();
    private final Map<UUID, Integer> targetPressure = new HashMap<>();

    public BotTargetSelector(Targets targets, RandomGenerator random) {
        this.targets = targets;
        this.random = random;
    }

    public void clear() {
        this.spatial.clear();
        this.targetPressure.clear();
    }

    public void index(List<Player> humans) {
        List<Player> all = new ArrayList<>(humans);
        all.addAll(this.targets.bots());
        for (Player player : all)
            this.spatial.computeIfAbsent(this.cell(player.getLocation()), key -> new ArrayList<>()).add(player);
    }

    public void release(UUID target) {
        this.targetPressure.computeIfPresent(target, (id, count) -> count <= 1 ? null : count - 1);
    }

    public void claim(UUID target) {
        this.targetPressure.merge(target, 1, Integer::sum);
    }

    public int pressure(UUID target) {
        return this.targetPressure.getOrDefault(target, 0);
    }

    public List<Player> at(Cell center, int offsetX, int offsetZ) {
        return this.spatial.getOrDefault(new Cell(center.world(), center.x() + offsetX, center.z() + offsetZ), List.of());
    }

    public Player search(
            Settings config,
            Player bot,
            Predicate<Player> validTarget,
            Predicate<UUID> pursuing,
            ToDoubleFunction<Player> preferenceFor
    ) {
        Location location = bot.getLocation();
        int radius = (int) Math.ceil(config.searchDistance() / 16);
        Cell center = cell(location);
        List<Candidate> candidates = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (Player candidate : spatial.getOrDefault(new Cell(center.world(), center.x() + x, center.z() + z),
                        List.of())) {
                    if (!validTarget.test(candidate)) continue;
                    double preference = preferenceFor.applyAsDouble(candidate);
                    if (!Double.isFinite(preference)) continue;
                    if (pursuing.test(candidate.getUniqueId())) {
                        preference += Math.log(3);
                    }
                    double distanceSquared = candidate.getLocation().distanceSquared(location);
                    candidates.add(new Candidate(candidate, distanceSquared, preference));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));
        if (candidates.isEmpty()) return null;
        double maximum = Math.log1p(candidates.getFirst().distanceSquared() + 4);
        List<Target> nearby = candidates.stream()
                .filter(candidate -> Math.log1p(candidate.distanceSquared()) - candidate.preference() <= maximum)
                .map(candidate -> new Target(candidate.player(), this.targetScore(candidate)))
                .sorted(Comparator.comparingDouble(Target::score))
                .limit(16)
                .filter(candidate -> bot.hasLineOfSight(candidate.player()))
                .limit(4)
                .toList();
        if (nearby.isEmpty()) return null;
        double bestScore = nearby.getFirst().score();
        double total = nearby.stream()
                .mapToDouble(candidate -> Math.exp(bestScore - candidate.score()))
                .sum();
        double choice = this.random.nextDouble(total);
        for (Target candidate : nearby) {
            choice -= Math.exp(bestScore - candidate.score());
            if (choice <= 0) return candidate.player();
        }
        return nearby.getLast().player();
    }

    private double targetScore(Candidate candidate) {
        return Math.log1p(
                candidate.distanceSquared() + Math.pow(targetPressure.getOrDefault(candidate.player().getUniqueId(), 0), 2) * 6
                        + this.targets.healthRatio(candidate.player()) * 2) - candidate.preference();
    }

    public Location separate(BotMovement.Settings config, Player bot, Location goal) {
        Location location = bot.getLocation();
        Cell center = this.cell(location);
        Vector offset = new Vector();
        int checked = 0;
        neighbors:
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (Player other : this.at(center, x, z)) {
                    if (other.equals(bot)) continue;
                    Location position = other.getLocation();
                    double dx = location.getX() - position.getX(), dz = location.getZ() - position.getZ();
                    double distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared >= 1.44 || Math.abs(location.getY() - position.getY()) > 2) continue;
                    if (distanceSquared < 0.0001) {
                        double angle = Math.floorMod(bot.getUniqueId().hashCode(), 360) * Math.PI / 180;
                        offset.add(new Vector(Math.cos(angle), 0, Math.sin(angle)));
                    } else {
                        offset.add(new Vector(dx, 0, dz).multiply((1.2 - Math.sqrt(distanceSquared)) / distanceSquared));
                    }
                    if (++checked >= 12) {
                        break neighbors;
                    }
                }
            }
        }
        double maximumOffset = Math.hypot(location.getX() - goal.getX(), location.getZ() - goal.getZ()) > 2 ? .35 : .9;
        if (offset.lengthSquared() > maximumOffset * maximumOffset) {
            offset.normalize().multiply(maximumOffset);
        }
        Location separated = goal.clone().add(offset);
        return BotMovement.distanceSquared(config, separated) <= config.radius() * config.radius() ? separated : goal;
    }

    public Cell cell(Location location) {
        return new Cell(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private record Candidate(Player player, double distanceSquared, double preference) {
    }

    private record Target(Player player, double score) {
    }

    public record Cell(UUID world, int x, int z) {
    }
}
