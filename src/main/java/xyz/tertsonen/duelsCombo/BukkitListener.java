package xyz.tertsonen.duelsCombo;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import xyz.tertsonen.duelsCombo.customItems.ItemFlag;
import xyz.tertsonen.duelsCombo.saveData.KitData;

import java.util.HashMap;
import java.util.Objects;

public class BukkitListener implements Listener {

	private final HashMap<Player, LaunchData> players = new HashMap<>();
	private final HashMap<Player, BukkitRunnable> canFire = new HashMap<>();

	public BukkitListener(){
		DuelsCombo.getInstance().getServer().getPluginManager().registerEvents(this, DuelsCombo.getInstance());
	}

	private void setCanFire(Integer cd, Player player){
		if(cd == null) return;
		BukkitRunnable prev = canFire.get(player);
		if(prev != null) prev.cancel();
		BukkitRunnable runnable = new BukkitRunnable(){
			@Override
			public void run() {
				canFire.remove(player);
			}
		};
		canFire.put(player, runnable);
		runnable.runTaskLater(DuelsCombo.getInstance(), cd);
	}

	@EventHandler
	private void onBowShoot(EntityShootBowEvent e){
		if(e.getBow() == null) return;
		Utils.setProjectileSpeedFromMultiplier(e.getBow(), e.getProjectile());
		Utils.passBowFlags(e.getBow(), e.getProjectile());
		if(!(e.getEntity() instanceof Player)) return;
		Player player = (Player) e.getEntity();
		setCanFire(
				ItemFlag.getInt(ItemFlag.TIME_BETWEEN_BOW_SHOTS, player.getInventory().getItemInMainHand().getItemMeta()),
				player);
	}

	@EventHandler
	private void onProjectileHit(ProjectileHitEvent e){
		Double kb = e.getEntity().getPersistentDataContainer().get(ItemFlag.SHOOTER_BOW_KNOCKBACK.getNamespacedKey(), PersistentDataType.DOUBLE);
		ProjectileSource shooterSource = e.getEntity().getShooter();
		if(!(shooterSource instanceof Player)) return;
		Player shooter = (Player) shooterSource;
		if(kb != null){
			Location projLoc = e.getEntity().getLocation();
			players.put(shooter, new LaunchData(projLoc, kb));
			new BukkitRunnable(){
				@Override
				public void run() {
					LaunchData dat = players.get(shooter);
					if(dat == null) return;
					if(dat.getArrowPos() == projLoc) players.remove(shooter);
				}
			}.runTaskLater(DuelsCombo.getInstance(), /* 30 seconds */30 * 20L);
		}
		Double explosionSize = ItemFlag.getDouble(ItemFlag.PROJECTILE_EXPLOSION_SIZE, e.getEntity());
		if(explosionSize != null && explosionSize != 0d){
			boolean breakBlocks = ItemFlag.getBool(ItemFlag.PROJECTILE_EXPLOSION_DESTROY_BLOCKS, e.getEntity());
			e.getEntity().getWorld().createExplosion(e.getEntity().getLocation(), (float) (double) explosionSize, false, breakBlocks);
		}

		if(e.getEntity() instanceof Arrow){
			Entity hitEntity = e.getHitEntity();
			if(hitEntity != null){
				Arrow arrow = (Arrow) e.getEntity();
				Short val = arrow.getPersistentDataContainer().get(ItemFlag.SWITCH_POSITIONS_ON_HIT.getNamespacedKey(), PersistentDataType.SHORT);
				if(val != null && val == ((short) 1)){
					new BukkitRunnable(){
						@Override
						public void run() {
							Location l2 = hitEntity.getLocation();
							Location oldHitEntityLoc = new Location(l2.getWorld(), l2.getX(), l2.getY(), l2.getZ());
							Location l3 = shooter.getLocation();
							Location shooterLoc = new Location(l3.getWorld(), l3.getX(), l3.getY(), l3.getZ());
							hitEntity.teleport(shooterLoc);
							shooter.teleport(oldHitEntityLoc);
						}
					}.runTaskLater(DuelsCombo.getInstance(), 1L);
				}
			}
		}
	}

	@EventHandler
	private void onLogin(PlayerLoginEvent e){
		//Just in case
		canFire.remove(e.getPlayer());
	}

	private void handleCooldown(@NotNull PlayerInteractEvent e){
		Player p = e.getPlayer();
		if(p.getGameMode() != GameMode.SPECTATOR && e.getItem() != null &&
				(e.getItem().getType() == Material.BOW || e.getItem().getType() == Material.CROSSBOW) &&
				e.getItem().getItemMeta() != null) {
			if(canFire.containsKey(p)){
				e.setUseItemInHand(Event.Result.DENY);
				return;
			}
			if(!ItemFlag.getBool(ItemFlag.BOW_INSTANT_SHOOT, e.getItem().getItemMeta())) return;
			e.setUseItemInHand(Event.Result.DENY);
			Integer cd = ItemFlag.getInt(ItemFlag.TIME_BETWEEN_BOW_SHOTS, e.getItem().getItemMeta());
			if(!canFire.containsKey(p)){
				Material arrow = Utils.takeFirstArrow(p, !(p.getGameMode() == GameMode.CREATIVE ||
						e.getItem().getEnchantmentLevel(Enchantment.ARROW_INFINITE) != 0));
				if(arrow != null){
					Class<? extends AbstractArrow> arrowClass;
					if (arrow == Material.SPECTRAL_ARROW) {
						arrowClass = SpectralArrow.class;
					} else {
						arrowClass = Arrow.class;
					}
					Projectile proj = p.launchProjectile(arrowClass);
					Utils.setProjectileSpeedFromMultiplier(e.getItem(), proj);
					Utils.passBowFlags(e.getItem(), proj);
					setCanFire(cd, e.getPlayer());
				}
			}
		}
	}

	@EventHandler
	private void onInteract(PlayerInteractEvent e){
		Player p = e.getPlayer();
		handleCooldown(e);
		ItemStack item = e.getItem();
		if(item == null) return;
		if((item.getType() == Material.BOW || item.getType() == Material.CROSSBOW) && e.useItemInHand() != Event.Result.DENY) return;
		LaunchData dat = players.remove(p);
		if(dat == null) return;
		Location playerLoc = p.getLocation();
		if(!Objects.equals(dat.getArrowPos().getWorld(), playerLoc.getWorld())) return;
		p.setVelocity(dat.getArrowPos().toVector().add(new Vector(0, 2, 0)).subtract(playerLoc.toVector()).multiply(-dat.getKb()));
		Utils.sendVelocityPacket(p);
	}

	@EventHandler
	private void onDamage(EntityDamageByEntityEvent e){
		if(!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
		Player hitter = (Player) e.getDamager();
		if(!Utils.checkInMatch(hitter, (Player) e.getEntity())) return;
		KitData kitData = Utils.getKitData(hitter);
		double kb = kitData.getKnockbackMultiplier();
		if(kb == 1) return;
		Vector kbDir = hitter.getLocation().getDirection();
		if(kbDir.getY() < 0) kbDir.setY(0);
		kbDir.setY(kbDir.getY() + kitData.getYKnockbackAmount());
		e.setCancelled(true);
		if(e.getEntity() instanceof LivingEntity){
			((LivingEntity) e.getEntity()).damage(e.getDamage());
		}

		//Check if velocity after applying kb is bigger than maxVelocity
		double maxKb = kitData.getMaxKnockbackSpeedMultiplier();
		Vector eVel = e.getEntity().getVelocity();
		Vector maxVel = eVel.clone().normalize().multiply(maxKb);
		Vector newEVel = eVel.clone().add(kbDir.multiply(kb));
		//Scalar projection
		double scalarProj = maxVel.dot(newEVel)/maxVel.length();
		double scalarWithoutAdd = maxVel.dot(eVel)/maxVel.length();

		if(scalarWithoutAdd < maxVel.length()){
			if(scalarProj < maxVel.length()){
				e.getEntity().setVelocity(newEVel);
			}else{
				e.getEntity().setVelocity(eVel.add(maxVel.subtract(eVel)));
			}
			Utils.sendVelocityPacket(hitter);
			if(e.getEntity() instanceof Player){
				Utils.sendVelocityPacket((Player) e.getEntity());
			}
		}
	}
}
