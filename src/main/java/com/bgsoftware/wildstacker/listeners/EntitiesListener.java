package com.bgsoftware.wildstacker.listeners;

import com.bgsoftware.wildstacker.Locale;
import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.enums.SpawnCause;
import com.bgsoftware.wildstacker.api.enums.StackSplit;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.hooks.MythicMobsHook;
import com.bgsoftware.wildstacker.hooks.ProtocolLibHook;
import com.bgsoftware.wildstacker.listeners.events.EntityBreedEvent;
import com.bgsoftware.wildstacker.listeners.events.EntityPickupItemEvent;
import com.bgsoftware.wildstacker.objects.WStackedEntity;
import com.bgsoftware.wildstacker.utils.EntityUtil;
import com.bgsoftware.wildstacker.utils.Executor;
import com.bgsoftware.wildstacker.utils.ItemStackList;
import com.bgsoftware.wildstacker.utils.ItemUtil;
import com.bgsoftware.wildstacker.utils.legacy.Materials;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SheepDyeWoolEvent;
import org.bukkit.event.entity.SheepRegrowWoolEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings({"unused", "WeakerAccess"})
public final class EntitiesListener implements Listener {

    private WildStackerPlugin plugin;

    public static Set<UUID> noStackEntities = new HashSet<>();

    public EntitiesListener(WildStackerPlugin plugin){
        this.plugin = plugin;
        if(plugin.getServer().getBukkitVersion().contains("1.13"))
            plugin.getServer().getPluginManager().registerEvents(new EntitiesListener1_13(), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeathMonitor(EntityDeathEvent e){
        if(e.getEntity().hasMetadata("corpse")){
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPickup(EntityPickupItemEvent e){
        if(e.getEntity().hasMetadata("corpse")){
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCustomEntityDeath(EntityDeathEvent e){
        EntityDamageEvent lastDamageCause = e.getEntity().getLastDamageCause();

        //Checks if the damage is null
        if(lastDamageCause != null)
            return;

        //EntityDamageEvent is null - it was killed by 'CUSTOM'
        //Calling the onEntityLastDamage function with default parameters.

        //noinspection unchecked
        EntityDamageEvent entityDamageEvent = new EntityDamageEvent(e.getEntity(), EntityDamageEvent.DamageCause.CUSTOM,
                new EnumMap(ImmutableMap.of(EntityDamageEvent.DamageModifier.BASE, e.getEntity().getHealth())),
                new EnumMap(ImmutableMap.of(EntityDamageEvent.DamageModifier.BASE, Functions.constant(-0.0D))));

        onEntityLastDamage(entityDamageEvent);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityLastDamage(EntityDamageEvent e){
        //Checks that it's the last hit of the entity
        if(!(e.getEntity() instanceof LivingEntity) || ((LivingEntity) e.getEntity()).getHealth() - e.getFinalDamage() > 0)
            return;

        if(e.getEntityType() == EntityType.ARMOR_STAND || e.getEntityType() == EntityType.PLAYER)
            return;

        LivingEntity livingEntity = (LivingEntity) e.getEntity();
        StackedEntity stackedEntity = WStackedEntity.of(livingEntity);

        if(plugin.getSettings().entitiesStackingEnabled || stackedEntity.getStackAmount() > 1) {
            EntityDamageEvent.DamageCause lastDamageCause = e.getCause();
            int lootBonusLevel = getFortuneLevel(livingEntity);
            int stackAmount = plugin.getSettings().entitiesInstantKills.contains(lastDamageCause.name()) ||
                    plugin.getSettings().entitiesInstantKills.contains(e.getEntityType().name()) ||
                    ((LivingEntity) e.getEntity()).getHealth() <= 0 ? stackedEntity.getStackAmount() : 1;

            if(plugin.getSettings().nextStackKnockback)
                e.setDamage(0);
            else
                e.setCancelled(true);

            livingEntity.setHealth(livingEntity.getMaxHealth());

            livingEntity.setLastDamageCause(e);

            if(stackedEntity.tryUnstack(stackAmount)) {
                if (e instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) e).getDamager() instanceof Player) {
                    EntityUtil.setKiller(livingEntity, (Player) ((EntityDamageByEntityEvent) e).getDamager());
                } else {
                    EntityUtil.setKiller(livingEntity, null);
                }

                Executor.async(() -> {
                    List<ItemStack> drops = new ItemStackList(stackedEntity.getDrops(lootBonusLevel, stackAmount)).toList();
                    Executor.sync(() -> drops.forEach(itemStack -> ItemUtil.dropItem(itemStack, livingEntity.getLocation())));
                });

                int exp = stackedEntity.getExp(stackAmount, -1);

                if (exp > 0) {
                    ExperienceOrb experienceOrb = livingEntity.getWorld().spawn(livingEntity.getLocation(), ExperienceOrb.class);
                    experienceOrb.setExperience(exp);
                }

                if (plugin.getSettings().keepFireEnabled && livingEntity.getFireTicks() > -1)
                    livingEntity.setFireTicks(160);

                if (livingEntity.getKiller() != null && stackAmount - 1 > 0) {
                    try {
                        livingEntity.getKiller().incrementStatistic(Statistic.MOB_KILLS, stackAmount);
                        livingEntity.getKiller().incrementStatistic(Statistic.KILL_ENTITY, stackedEntity.getType(), stackAmount);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(CreatureSpawnEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled)
            return;

        if(e.getEntityType() == EntityType.ARMOR_STAND || e.getEntityType() == EntityType.PLAYER || e.getEntity().hasMetadata("corpse"))
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        stackedEntity.setSpawnCause(SpawnCause.valueOf(e.getSpawnReason()));

        if(stackedEntity.isBlacklisted() || !stackedEntity.isWhitelisted() || stackedEntity.isWorldDisabled())
            return;

        if(noStackEntities.contains(e.getEntity().getUniqueId())) {
            noStackEntities.remove(e.getEntity().getUniqueId());
            return;
        }

        //EpicSpawners has it's own event
        if(stackedEntity.getSpawnCause() == SpawnCause.EPIC_SPAWNERS)
            return;

        if (!Bukkit.getPluginManager().isPluginEnabled("MergedSpawner") &&
                plugin.getSettings().linkedEntitiesEnabled && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER)
            return;

        //Need to add a delay so eggs will get removed from inventory
        if(e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || e.getEntityType() == EntityType.WITHER ||
                e.getEntityType() == EntityType.IRON_GOLEM || e.getEntityType() == EntityType.SNOWMAN || MythicMobsHook.isEnabled())
            Executor.sync(stackedEntity::tryStack, 1L);
        else
            stackedEntity.tryStack();

        //Chunk Limit
        Executor.sync(() -> {
            if(isChunkLimit(e.getLocation().getChunk()))
                stackedEntity.remove();
        }, 2L);
    }

    private int nextEntityStackAmount = -1;
    private EntityType nextEntityType = null;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerEggUse(PlayerInteractEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || e.getItem() == null || e.getAction() != Action.RIGHT_CLICK_BLOCK ||
                plugin.getSettings().blacklistedEntitiesSpawnReasons.contains("SPAWNER_EGG") || !Materials.isValidAndSpawnEgg(e.getItem()))
            return;

        nextEntityStackAmount = ItemUtil.getSpawnerItemAmount(e.getItem());
        nextEntityType = ItemUtil.getEntityType(e.getItem());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawnFromEgg(CreatureSpawnEvent e){
        if(e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || nextEntityStackAmount < 0 ||
                !e.getEntityType().equals(nextEntityType))
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        stackedEntity.setStackAmount(nextEntityStackAmount, true);

        nextEntityStackAmount = -1;
        nextEntityType = null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent e){
        //Fixes a async catch exception with auto-clear task
        if(!Bukkit.isPrimaryThread())
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSheepDye(SheepDyeWoolEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !StackSplit.SHEEP_DYE.isEnabled())
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
        int amount = stackedEntity.getStackAmount();

        DyeColor originalColor = e.getEntity().getColor();

        Executor.sync(() -> {
            if(amount > 1) {
                e.getEntity().setColor(originalColor);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicate = stackedEntity.spawnDuplicate(1);
                ((Sheep) duplicate.getLivingEntity()).setColor(e.getColor());
                duplicate.tryStack();
            }else{
                stackedEntity.tryStack();
            }
        });
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerShear(PlayerShearEntityEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !(e.getEntity() instanceof LivingEntity))
            return;

        if(!StackSplit.MUSHROOM_SHEAR.isEnabled() && e.getEntity() instanceof MushroomCow)
            return;

        if(!StackSplit.SHEEP_SHEAR.isEnabled() && e.getEntity() instanceof Sheep)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        if(e.getEntity() instanceof MushroomCow){
            stackedEntity.spawnDuplicate(stackedEntity.getStackAmount() - 1);
            stackedEntity.remove();
        }

        else if(e.getEntity() instanceof Sheep){
            int amount = stackedEntity.getStackAmount();
            Executor.sync(() -> {
                if(amount > 1) {
                    ((Sheep) e.getEntity()).setSheared(false);
                    stackedEntity.setStackAmount(amount - 1, true);
                    StackedEntity duplicate = stackedEntity.spawnDuplicate(1);
                    ((Sheep) duplicate.getLivingEntity()).setSheared(true);
                    duplicate.tryStack();
                }else{
                    stackedEntity.tryStack();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWoolRegrow(SheepRegrowWoolEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled)
            return;

        StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

        int amount = stackedEntity.getStackAmount();

        if(!e.getEntity().isSheared())
            return;

        Executor.sync(() -> {
            if(amount > 1){
                e.getEntity().setSheared(true);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                ((Sheep) duplicated.getLivingEntity()).setSheared(false);
                duplicated.tryStack();
            }else{
                stackedEntity.tryStack();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityNameTag(PlayerInteractAtEntityEvent e){
        if(!plugin.getSettings().entitiesStackingEnabled || !(e.getRightClicked() instanceof LivingEntity) || !StackSplit.NAME_TAG.isEnabled())
            return;

        ItemStack inHand = e.getPlayer().getInventory().getItemInHand();
        StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());

        if(inHand == null || inHand.getType() != Material.NAME_TAG || !inHand.hasItemMeta() || !inHand.getItemMeta().hasDisplayName()
                || e.getRightClicked() instanceof EnderDragon || e.getRightClicked() instanceof Player)
                return;

        int amount = stackedEntity.getStackAmount();

        Executor.sync(() -> {
            if(amount > 1){
                stackedEntity.setCustomName("");
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                duplicated.setCustomName(inHand.getItemMeta().getDisplayName());
                duplicated.tryStack();
            }else{
                stackedEntity.tryStack();
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityFeed(PlayerInteractAtEntityEvent e){
        if(!StackSplit.ENTITY_BREED.isEnabled())
            return;

        if(!(e.getRightClicked() instanceof Animals))
            return;

        Executor.sync(() -> {
            if(!plugin.getNMSAdapter().isInLove(e.getRightClicked()))
                return;

            StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());
            int amount = stackedEntity.getStackAmount();

            if(amount > 1){
                plugin.getNMSAdapter().setInLove(stackedEntity.getLivingEntity(), e.getPlayer(), false);
                stackedEntity.setStackAmount(amount - 1, true);
                StackedEntity duplicated = stackedEntity.spawnDuplicate(1);
                plugin.getNMSAdapter().setInLove(duplicated.getLivingEntity(), e.getPlayer(), true);
            }
        }, 1L);
    }

    @EventHandler
    public void onEntityBreed(EntityBreedEvent e){
        if(plugin.getSettings().stackAfterBreed) {
            StackedEntity motherEntity = WStackedEntity.of(e.getMother());
            StackedEntity fatherEntity = WStackedEntity.of(e.getFather());
            if(fatherEntity.tryStackInto(motherEntity)){
                ((Animals) motherEntity.getLivingEntity()).setBreed(false);
                motherEntity.tryStack();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e){
        if(e.getEntity() instanceof Animals)
            try{
                plugin.getNMSAdapter().addCustomPathfinderGoalBreed(e.getEntity());
            }catch(UnsupportedOperationException ignored){}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent e){
        if(e.getEntity() instanceof LivingEntity) {
            StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());
            if (stackedEntity.isNerfed()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityInspect(PlayerInteractAtEntityEvent e){
        if(isOffHand(e) || e.getPlayer().getItemInHand() == null || !e.getPlayer().getItemInHand().isSimilar(plugin.getSettings().inspectTool) ||
                !(e.getRightClicked() instanceof LivingEntity))
            return;

        e.setCancelled(true);

        StackedEntity stackedEntity = WStackedEntity.of(e.getRightClicked());

        Locale.ENTITY_INFO_HEADER.send(e.getPlayer());
        Locale.ENTITY_INFO_UUID.send(e.getPlayer(), stackedEntity.getUniqueId());
        Locale.ENTITY_INFO_TYPE.send(e.getPlayer(), EntityUtil.getFormattedType(stackedEntity.getType().name()));
        Locale.ENTITY_INFO_AMOUNT.send(e.getPlayer(), stackedEntity.getStackAmount());
        Locale.ENTITY_INFO_SPAWN_REASON.send(e.getPlayer(), stackedEntity.getSpawnCause().name());
        Locale.ENTITY_INFO_NERFED.send(e.getPlayer(), stackedEntity.isNerfed() ? "True" : "False");
        Locale.BARREL_INFO_FOOTER.send(e.getPlayer());
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent e){
        if(!plugin.getSettings().entitiesNamesToggleEnabled)
            return;

        String commandSyntax = "/" + plugin.getSettings().entitiesNamesToggleCommand;

        if(!e.getMessage().equalsIgnoreCase(commandSyntax) && !e.getMessage().startsWith(commandSyntax + " "))
            return;

        e.setCancelled(true);

        if(!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")){
            e.getPlayer().sendMessage(ChatColor.RED + "The command is enabled but ProtocolLib is not installed. Please contact the administrators of the server to solve the issue.");
            return;
        }

        if(ProtocolLibHook.entitiesDisabledNames.contains(e.getPlayer().getUniqueId())){
            ProtocolLibHook.entitiesDisabledNames.remove(e.getPlayer().getUniqueId());
            Locale.ENTITY_NAMES_TOGGLE_ON.send(e.getPlayer());
        }
        else{
            ProtocolLibHook.entitiesDisabledNames.add(e.getPlayer().getUniqueId());
            Locale.ENTITY_NAMES_TOGGLE_OFF.send(e.getPlayer());
        }

        //Refresh item names
        for(Entity entity : e.getPlayer().getNearbyEntities(50, 256, 50)){
            if(entity instanceof LivingEntity && entity.isCustomNameVisible()){
                ProtocolLibHook.updateName(e.getPlayer(), entity);
            }
        }
    }

    private boolean isOffHand(PlayerInteractAtEntityEvent event){
        try{
            return event.getClass().getMethod("getHand").invoke(event).toString().equals("OFF_HAND");
        }catch(Throwable ex){
            return true;
        }
    }

    private EntityDamageEvent.DamageCause getLastDamage(LivingEntity livingEntity){
        return livingEntity.getLastDamageCause() == null ? EntityDamageEvent.DamageCause.CUSTOM : livingEntity.getLastDamageCause().getCause();
    }

    private boolean isChunkLimit(Chunk chunk){
        int chunkLimit = plugin.getSettings().entitiesChunkLimit;

        if(chunkLimit <= 0)
            return false;

        int entitiesInsideChunk = (int) Arrays.stream(chunk.getEntities()).filter(entity -> entity instanceof LivingEntity).count();
        return entitiesInsideChunk > chunkLimit;
    }

    private int getFortuneLevel(LivingEntity livingEntity){
        int fortuneLevel = 0;

        if(getLastDamage(livingEntity) == EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
                livingEntity.getKiller() != null && livingEntity.getKiller().getItemInHand() != null){
            fortuneLevel = livingEntity.getKiller().getItemInHand().getEnchantmentLevel(Enchantment.LOOT_BONUS_MOBS);
        }

        return fortuneLevel;
    }

    class EntitiesListener1_13 implements Listener {

        @EventHandler
        public void onEntityTransform(EntityTransformEvent e){
            StackedEntity stackedEntity = WStackedEntity.of(e.getEntity());

            if(stackedEntity.getStackAmount() > 1){
                StackedEntity transformed = WStackedEntity.of(e.getTransformedEntity());
                transformed.setStackAmount(stackedEntity.getStackAmount(), true);
                stackedEntity.remove();
            }
        }

    }

}
