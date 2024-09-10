package com.clonz.blastfromthepast.entity;

import com.clonz.blastfromthepast.BlastFromThePast;
import com.clonz.blastfromthepast.client.models.FrostomperModel;
import com.clonz.blastfromthepast.entity.ai.*;
import com.clonz.blastfromthepast.entity.ai.attacker.AnimatedMeleeAttackGoal;
import com.clonz.blastfromthepast.entity.ai.attacker.AttackerBodyRotationControl;
import com.clonz.blastfromthepast.entity.pack.EntityPack;
import com.clonz.blastfromthepast.entity.ai.navigation.BFTPGroundPathNavigation;
import com.clonz.blastfromthepast.entity.pack.EntityPackAgeableMobData;
import com.clonz.blastfromthepast.entity.pack.EntityPackHolder;
import com.clonz.blastfromthepast.init.ModEntities;
import com.clonz.blastfromthepast.init.ModTags;
import com.clonz.blastfromthepast.mixin.AbstractChestedHorseAccessor;
import com.clonz.blastfromthepast.util.DebugFlags;
import com.clonz.blastfromthepast.util.EntityHelper;
import com.clonz.blastfromthepast.util.HitboxHelper;
import io.github.itskillerluc.duclib.client.animation.DucAnimation;
import io.github.itskillerluc.duclib.entity.Animatable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.common.util.Lazy;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class FrostomperEntity extends AbstractChestedHorse implements Animatable<FrostomperModel>, EntityPackHolder<FrostomperEntity>, AnimatedAttacker<FrostomperEntity, FrostomperEntity.FrostomperAttackType>, ChargeForward {
    public static final DucAnimation ADULT_ANIMATION = DucAnimation.create(ModEntities.FROSTOMPER.getId());
    public static final DucAnimation BABY_ANIMATION = DucAnimation.create(ModEntities.FROSTOMPER.getId().withPrefix("baby_"));
    public static final EntityDimensions BABY_FROSTOMPER_DIMENSIONS = EntityDimensions.scalable(HitboxHelper.pixelsToBlocks(28.0F), HitboxHelper.pixelsToBlocks(22.0F));
    private static final EntityDataAccessor<OptionalInt> DATA_ACTIVE_ATTACK_TYPE = SynchedEntityData.defineId(FrostomperEntity.class, EntityDataSerializers.OPTIONAL_UNSIGNED_INT);
    private static final EntityDataAccessor<Boolean> DATA_CHARGING_FORWARD = SynchedEntityData.defineId(FrostomperEntity.class, EntityDataSerializers.BOOLEAN);
    private static final double PARENT_TARGETING_DISTANCE = 16.0D;
    private static final int CHARGE_ATTACK_COOLDOWN = 900;
    private static final UniformInt CHARGE_ATTACK_DURATION = UniformInt.of(Mth.floor(20 / 0.3F), Mth.floor(25 / 0.3F)); // Distances in blocks divided by Frostomper's base speed of 0.3 blocks/tick
    private final Lazy<Map<String, AnimationState>> babyAnimations = Lazy.of(() -> FrostomperModel.createStateMap(BABY_ANIMATION));
    private final Lazy<Map<String, AnimationState>> adultAnimations = Lazy.of(() -> FrostomperModel.createStateMap(ADULT_ANIMATION));
    protected static final TargetingConditions PARENT_TARGETING = TargetingConditions.forNonCombat()
            .ignoreLineOfSight()
            .selector(entity -> entity instanceof FrostomperEntity && ((FrostomperEntity)entity).isBred());
    protected final TargetingConditions parentTargeting;
    @Nullable
    private EntityPack<FrostomperEntity> pack;
    private final AnimatedAttacker.AttackTicker<FrostomperEntity, FrostomperAttackType> attackTicker = new AttackTicker<>(this);
    private int ticksUntilNextCharge;

    public FrostomperEntity(EntityType<? extends FrostomperEntity> entityType, Level level) {
        super(entityType, level);
        this.setPathfindingMalus(PathType.LEAVES, 0.0F);
        this.parentTargeting = PARENT_TARGETING.copy().selector(entity -> HitboxHelper.isCloseEnoughForTargeting(this, entity, true, PARENT_TARGETING_DISTANCE));
        ((AbstractChestedHorseAccessor)this).setBabyDimensions(BABY_FROSTOMPER_DIMENSIONS);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return AgeableMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.5)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    public static void init() {
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new WithoutTargetPanicGoal(this, 1.2));
        //this.goalSelector.addGoal(1, new RunAroundLikeCrazyGoal(this, 1.2));
        this.goalSelector.addGoal(2, new HitboxAdjustedBreedGoal(this, 1.0));
        this.goalSelector.addGoal(3, new HitboxAdjustedFollowParentGoal(this, 1.0));
        this.goalSelector.addGoal(4, new ChargeForwardAttackGoal<>(this, CHARGE_ATTACK_DURATION, 1.2));
        this.goalSelector.addGoal(5, new AnimatedMeleeAttackGoal<>(this, 1.0, true));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        if (this.canPerformRearing()) {
            this.goalSelector.addGoal(9, new RandomStandGoal(this));
        }
        this.addBehaviourGoals();
        this.targetSelector.addGoal(0, new PackHurtByTargetGoal<>(this, AgeableMob::isBaby, FrostomperEntity.class));
    }

    @Override
    protected void updateControlFlags() {
        boolean canControlMove = this.getControllingPassenger() == null;
        boolean canControlJump = !(this.getVehicle() instanceof Boat);
        this.goalSelector.setControlFlag(Goal.Flag.MOVE, canControlMove);
        this.goalSelector.setControlFlag(Goal.Flag.JUMP, canControlMove && canControlJump);
        this.goalSelector.setControlFlag(Goal.Flag.LOOK, canControlMove);
        this.goalSelector.setControlFlag(Goal.Flag.TARGET, canControlMove);
    }

    @Override
    protected void addBehaviourGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(3, new TemptGoal(this, 1.25, this::isTemptItem, false));
    }

    protected boolean isTemptItem(ItemStack stack) {
        return stack.is(this.isBaby() ? ModTags.Items.BABY_FROSTOMPER_TEMPT_ITEMS : ModTags.Items.FROSTOMPER_TEMPT_ITEMS);
    }

    @Override
    protected void randomizeAttributes(RandomSource random) {
        // TODO: Implement randomized attributes logic
    }

    @Override
    protected void followMommy() {
        if (this.isBred() && this.isBaby() && !this.isEating()) {
            FrostomperEntity mommy = this.level()
                    .getNearestEntity(FrostomperEntity.class, this.parentTargeting, this, this.getX(), this.getY(), this.getZ(), this.getBoundingBox().inflate(PARENT_TARGETING_DISTANCE));
            if (mommy != null && HitboxHelper.getDistSqrBetweenHitboxes(this, mommy) > 4.0D) {
                this.navigation.createPath(mommy, 0);
            }
        }
    }

    @Override
    protected void doPlayerRide(Player player) {
        if(this.isTamed()){
            super.doPlayerRide(player);
        }
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new BFTPGroundPathNavigation(this, level);
    }

    public int getMaxHeadYRot() {
        return 45;
    }

    @Override
    public ResourceLocation getModelLocation() {
        return null;
    }

    @Override
    public DucAnimation getAnimation() {
        return this.isBaby() ? BABY_ANIMATION : ADULT_ANIMATION;
    }

    @Override
    public Lazy<Map<String, AnimationState>> getAnimations() {
        return this.isBaby() ? this.babyAnimations : this.adultAnimations;
    }

    @Override
    public Optional<AnimationState> getAnimationState(String animation) {
        return Optional.ofNullable(this.getAnimations().get().get(this.getAnimationKey(animation)));
    }

    public String getAnimationKey(String animation) {
        return this.isBaby() ? "animation.baby_frostomper." + animation : "animation.frostomper." + animation;
    }

    @Override
    public int tickCount() {
        return this.tickCount;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.isAlive()) {
            if (this.horizontalCollision && EventHooks.canEntityGrief(this.level(), this)) {
                boolean destroyedBlock = false;
                AABB breakBox = this.getBoundingBox().inflate(0.2);
                Iterator<BlockPos> nearbyBlockPositions = BlockPos.betweenClosed(Mth.floor(breakBox.minX), Mth.floor(breakBox.minY), Mth.floor(breakBox.minZ), Mth.floor(breakBox.maxX), Mth.floor(breakBox.maxY), Mth.floor(breakBox.maxZ)).iterator();

                breakNearbyBlocks:
                while(true) {
                    BlockPos nearbyBlockPos;
                    BlockState nearbyBlockState;
                    do {
                        if (!nearbyBlockPositions.hasNext()) {
                            /*
                            if (!destroyedBlock && this.onGround()) {
                                this.jumpFromGround();
                            }
                             */
                            break breakNearbyBlocks;
                        }

                        nearbyBlockPos = nearbyBlockPositions.next();
                        nearbyBlockState = this.level().getBlockState(nearbyBlockPos);
                    } while(!(nearbyBlockState.is(ModTags.Blocks.FROSTOMPER_CAN_BREAK)));

                    destroyedBlock = this.level().destroyBlock(nearbyBlockPos, true, this) || destroyedBlock;
                }
            }
        }
    }

    @Override
    public boolean isFood(ItemStack itemStack) {
        return itemStack.is(this.isBaby() ? ModTags.Items.BABY_FROSTOMPER_FOOD : ModTags.Items.FROSTOMPER_FOOD);
    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel serverLevel, AgeableMob otherParent) {
        FrostomperEntity offspring = ModEntities.FROSTOMPER.get().create(serverLevel);
        if(offspring != null){
            this.setOffspringAttributes(otherParent, offspring);
        }
        return offspring;
    }

    @Override
    protected void setOffspringAttributes(AgeableMob parent, AbstractHorse child) {
        // TODO: Implement randomized offspring attributes logic
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        if (spawnGroupData == null) {
            spawnGroupData = new FrostomperEntity.FrostomperGroupData(BlastFromThePast.getUniversalEntityPacks(level.getLevel().getServer()).createFreshPack(), true);
        }

        if(spawnGroupData instanceof FrostomperGroupData frostomperGroupData){
            frostomperGroupData.addPackMember(this);
        }
        SpawnGroupData spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        if(this.isBaby()){
            this.setAge(AgeableMob.BABY_START_AGE * 3);
        }
        return spawnData;
    }

    @Override
    public void setBaby(boolean baby) {
        this.setAge(baby ? AgeableMob.BABY_START_AGE * 3 : 0);
    }

    @Override
    public int getMaxSpawnClusterSize() {
        return 6;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.DONKEY_AMBIENT;
    }

    @Override
    protected SoundEvent getAngrySound() {
        return SoundEvents.DONKEY_ANGRY;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.DONKEY_DEATH;
    }

    @Override
    @Nullable
    protected SoundEvent getEatingSound() {
        return SoundEvents.DONKEY_EAT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.DONKEY_HURT;
    }

    @Override
    protected void playJumpSound() {
        this.playSound(SoundEvents.DONKEY_JUMP, 0.4F, 1.0F);
    }

    @Override
    protected void playChestEquipsSound() {
        this.playSound(SoundEvents.DONKEY_CHEST, 1.0F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
    }

    @Override
    public SoundEvent getSaddleSoundEvent() {
        return SoundEvents.HORSE_SADDLE;
    }

    @Override
    protected boolean handleEating(Player player, ItemStack stack) {
        if (!this.isFood(stack)) {
            return false;
        } else {
            boolean fed = false;
            if (this.getHealth() < this.getMaxHealth()) {
                this.heal(2.0F);
                fed = true;
            }
            if (this.getAge() == 0 && this.canFallInLove()) {
                this.setInLove(player);
                fed = true;
            }
            if (this.isBaby()) {
                this.level().addParticle(ParticleTypes.HAPPY_VILLAGER, this.getRandomX(1.0), this.getRandomY() + 0.5, this.getRandomZ(1.0), 0.0, 0.0, 0.0);
                if (!this.level().isClientSide) {
                    this.ageUp(10);
                    fed = true;
                }
            }
            if (!this.isTamed() && this.getTemper() < this.getMaxTemper() && !this.level().isClientSide) {
                this.modifyTemper(10);
                if (this.getTemper() >= this.getMaxTemper() && this.canBeTamed() && !EventHooks.onAnimalTame(this, player)) {
                    this.tameWithName(player);
                }
                fed = true;
            }

            if (!fed) {
                return false;
            } else {
                if (!this.isSilent()) {
                    SoundEvent eatingSound = this.getEatingSound();
                    if (eatingSound != null) {
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), eatingSound, this.getSoundSource(), 1.0F, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.2F);
                    }
                }

                this.gameEvent(GameEvent.EAT);
                return true;
            }
        }
    }

    @Override
    public void finalizeSpawnChildFromBreeding(ServerLevel level, Animal animal, @Nullable AgeableMob baby) {
        super.finalizeSpawnChildFromBreeding(level, animal, baby);
        if(baby instanceof FrostomperEntity babyFrostomper){
            Optional.ofNullable(this.getLoveCause())
                    .or(() -> Optional.ofNullable(animal.getLoveCause()))
                    .ifPresent(loveCause -> babyFrostomper.setBred(true));
        }
    }

    protected boolean canBeTamed() {
        return this.isBaby() || this.isBred();
    }

    @Override
    protected boolean canPerformRearing() {
        return false;
    }

    @Override
    public boolean canMate(Animal otherAnimal) {
        if (otherAnimal != this && otherAnimal instanceof FrostomperEntity frostomper) {
            return this.canParent() && frostomper.canParent();
        }

        return false;
    }

    @Override
    @Nullable
    public EntityPack<FrostomperEntity> getPack() {
        return this.pack;
    }

    @Override
    public void setPack(@Nullable EntityPack<FrostomperEntity> pack) {
        this.pack = pack;
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (this.level() instanceof ServerLevel serverLevel) {
            this.readPackData(compound, this, serverLevel);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        this.savePackData(compound);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTIVE_ATTACK_TYPE, OptionalInt.empty());
        builder.define(DATA_CHARGING_FORWARD, false);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> pKey) {
        super.onSyncedDataUpdated(pKey);
        if(DATA_ACTIVE_ATTACK_TYPE.equals(pKey)){
            this.attackTicker.reset();
        }
        if (!this.firstTick && DATA_CHARGING_FORWARD.equals(pKey)) {
            this.ticksUntilNextCharge = this.ticksUntilNextCharge == 0 ? CHARGE_ATTACK_COOLDOWN : this.ticksUntilNextCharge;
        }
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new AttackerBodyRotationControl<>(this);
    }

    @Override
    public void setActiveAttackType(@Nullable FrostomperAttackType attackType){
        this.entityData.set(DATA_ACTIVE_ATTACK_TYPE, attackType == null ? OptionalInt.empty() : OptionalInt.of(attackType.ordinal()));
    }

    @Override
    @Nullable
    public FrostomperAttackType getActiveAttackType(){
        OptionalInt ordinal = this.entityData.get(DATA_ACTIVE_ATTACK_TYPE);
        return ordinal.isEmpty() ? null : FrostomperAttackType.byOrdinal(ordinal.getAsInt());
    }

    @Override
    public void tick() {
        super.tick();
        FrostomperAttackType activeAttackType = this.getActiveAttackType();
        if (this.level().isClientSide()) {
            this.animateWhen("idle", !this.isMoving(this) && this.onGround() && activeAttackType == null);
            if(!this.isBaby()){
                this.animateWhen("double_stomp", activeAttackType == FrostomperAttackType.DOUBLE_STOMP);
                this.animateWhen("stomp", activeAttackType == FrostomperAttackType.SINGLE_STOMP && !this.isLeftHanded());
                this.animateWhen("stomp_flipped", activeAttackType == FrostomperAttackType.SINGLE_STOMP && this.isLeftHanded());
                this.animateWhen("fling", activeAttackType == FrostomperAttackType.FLING);
                this.animateWhen("charge", activeAttackType == FrostomperAttackType.CHARGE);
            }
        }
        if (activeAttackType != null && activeAttackType.blocksHeadRotation()) {
            this.clampHeadRotationToBody();
        }
        this.attackTicker.tick();
        // tick charge cooldown
        if (this.ticksUntilNextCharge > 0) {
            --this.ticksUntilNextCharge;
            if (this.ticksUntilNextCharge == 0) {
                this.level().playSound(null, this.blockPosition(), SoundEvents.CAMEL_DASH_READY, SoundSource.NEUTRAL, 1.0F, 1.0F);
            }
        }
    }

    @Override
    public void travel(Vec3 travelVector) {
        FrostomperAttackType attackType = this.getActiveAttackType();
        if (attackType != null && attackType.blocksMovementInput() && this.onGround()) {
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));
            travelVector = travelVector.multiply(0.0D, 1.0D, 0.0D);
        }
        super.travel(travelVector);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return this.getOwner() != target && super.canAttack(target);
    }

    @Override
    public PlayerTeam getTeam() {
        if (this.isTamed()) {
            LivingEntity owner = this.getOwner();
            if (owner != null) {
                return owner.getTeam();
            }
        }
        return super.getTeam();
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        if(other == null){
            return false;
        }

        if (this.isTamed()) {
            LivingEntity owner = this.getOwner();
            if (other == owner) {
                return true;
            }
            if (owner != null) {
                return owner.isAlliedTo(other);
            }
        }

        if (super.isAlliedTo(other)) {
            return true;
        } else if (this.isAlliedToDefault(other)) {
            return this.getTeam() == null && other.getTeam() == null;
        } else {
            return false;
        }
    }

    protected boolean isAlliedToDefault(Entity other) {
        return other.getType().equals(this.getType());
    }

    @Override
    public FrostomperAttackType selectAttackTypeForTarget(@Nullable LivingEntity target) {
        if(target != null){
            boolean canCharge = DebugFlags.DEBUG_CHARGE_FORWARD || this.ticksUntilNextCharge <= 0;
            int randomInt;
            if(canCharge){
                randomInt = this.random.nextInt(10);
                if(randomInt < 7){
                    return FrostomperAttackType.CHARGE;
                } else if(randomInt < 9){
                    return FrostomperAttackType.FLING;
                } else{
                    return FrostomperAttackType.DOUBLE_STOMP;
                }
            } else{
                randomInt = this.random.nextInt(3);
                if(randomInt < 2){
                    return FrostomperAttackType.FLING;
                } else{
                    return FrostomperAttackType.DOUBLE_STOMP;
                }
            }
        }
        return null;
    }



    @Override
    protected Vec2 getRiddenRotation(LivingEntity entity) {
        FrostomperAttackType activeAttackType = this.getActiveAttackType();
        boolean rotationBlocked = activeAttackType != null && activeAttackType.blocksRotationInput();
        return rotationBlocked ? new Vec2(this.getXRot(), this.getYRot()) : super.getRiddenRotation(entity);
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 travelVector) {
        FrostomperAttackType activeAttackType = this.getActiveAttackType();
        boolean movementBlocked = activeAttackType != null && activeAttackType.blocksMovementInput();
        return movementBlocked ? Vec3.ZERO : super.getRiddenInput(player, travelVector);
    }

    @Override
    public boolean isChargingForward() {
        return this.entityData.get(DATA_CHARGING_FORWARD);
    }

    @Override
    public void setIsChargingForward(boolean charging) {
        this.entityData.set(DATA_CHARGING_FORWARD, charging);
    }

    @Override
    public boolean canSpawnSprintParticle() {
        if(this.isChargingForward()){
            return true;
        } else{
            return super.canSpawnSprintParticle();
        }
    }

    @Override
    public boolean canJump() {
        return this.getActiveAttackType() == null && super.canJump();
    }

    @Override
    public void handleStartJump(int jumpPower) {
        if(jumpPower >= 90) {
            this.setActiveAttackType(FrostomperAttackType.DOUBLE_STOMP);
        } else{
            this.setActiveAttackType(FrostomperAttackType.SINGLE_STOMP);
        }
    }

    @Override
    public int getJumpCooldown() {
        return this.attackTicker.get();
    }

    @Override
    protected boolean canParent() {
        // does not need to be tamed to parent
        return !this.isVehicle() && !this.isPassenger() && !this.isBaby() && this.getHealth() >= this.getMaxHealth() && this.isInLove();
    }

    protected static class FrostomperGroupData extends EntityPackAgeableMobData<FrostomperEntity> {
        public FrostomperGroupData(EntityPack<FrostomperEntity> entityPack, boolean shouldSpawnBaby) {
            super(entityPack, shouldSpawnBaby);
        }
    }

    // Calculated from doubling (the center-to_corner distance of the Frostomper's hitbox (2.8938345), minus half of the Frostomper's hitbox width (2.04625))
    private static final double MINIMUM_ATTACK_SIZE = 1.695169D;
    // Adding 1 to the minimum attack size allows the Frostomper to attack targets whose hitboxes are up to 0.5F blocks away from one of its hitbox's corners
    private static final Vec3 DEFAULT_ATTACK_SIZE = new Vec3(MINIMUM_ATTACK_SIZE + 1, MINIMUM_ATTACK_SIZE + 1, MINIMUM_ATTACK_SIZE + 1);
    public enum FrostomperAttackType implements AttackType<FrostomperEntity, FrostomperAttackType>{
        FLING(Mth.floor(0.38F * 20), Mth.floor(0.75F * 20), DEFAULT_ATTACK_SIZE, 8, 1.5F),
        SINGLE_STOMP(Mth.floor(0.63F * 20), 20, DEFAULT_ATTACK_SIZE.scale(0.5D), 5, 0.75F),
        DOUBLE_STOMP(Mth.floor(0.63F * 20), 20, DEFAULT_ATTACK_SIZE, 10, 1.5F),
        CHARGE(0, 10, DEFAULT_ATTACK_SIZE, 12, 1.5F);

        private final int attackPoint;
        private final int attackDuration;
        private final Vec3 attackSize;
        private final float attackDamage;
        private final float attackKnockback;

        FrostomperAttackType(int attackPoint, int attackDuration, Vec3 attackSize, float attackDamage, float attackKnockback) {
            this.attackPoint = attackPoint;
            this.attackDuration = attackDuration;
            this.attackSize = attackSize;
            this.attackDamage = attackDamage;
            this.attackKnockback = attackKnockback;
        }

        @Override
        public int getAttackPoint() {
            return this.attackPoint;
        }

        @Override
        public int getAttackDuration() {
            return this.attackDuration;
        }

        @Override
        public Vec3 getAttackSize(){
            return this.attackSize;
        }

        @Override
        public float getAttackDamage() {
            return this.attackDamage;
        }

        @Override
        public float getAttackKnockback() {
            return this.attackKnockback;
        }

        @Override
        public boolean blocksMovementInput() {
            return this == DOUBLE_STOMP;
        }

        @Override
        public boolean blocksWalkAnimation() {
            return this == DOUBLE_STOMP || this == CHARGE;
        }

        @Override
        public boolean blocksRotationInput() {
            return this == DOUBLE_STOMP;
        }

        @Override
        public boolean blocksBodyRotation() {
            return this == DOUBLE_STOMP;
        }

        @Override
        public boolean blocksHeadRotation() {
            return this == DOUBLE_STOMP || this == FLING || this == CHARGE;
        }

        @Override
        public boolean hasAttackPointAt(int attackTicker) {
            if(this == CHARGE){
                return true;
            }
            return attackTicker == this.getAttackPoint();
        }

        @Override
        public void executeAttackPoint(FrostomperEntity attacker, int attackTicker) {
            AABB attackBounds = HitboxHelper.createHitboxRelativeToFront(attacker, this.getAttackSize().x(), this.getAttackSize().y(), this.getAttackSize().z());
            if(this == CHARGE){
                if(attackTicker == 0){
                    if(!attacker.level().isClientSide){
                        attacker.setIsChargingForward(true);
                    }
                }
                if(attacker.isChargingForward()){
                    // Passed in zero for attack knockback to prevent normal attack knockback application to targets
                    List<LivingEntity> hitTargets = EntityHelper.hitTargetsWithAOEAttack(attacker, attackBounds, this.getAttackDamage(), 0, false);
                    for(LivingEntity hitTarget : hitTargets){
                        // Now apply the attack knockback value to targets to push them back significantly
                        EntityHelper.strongKnockback(attacker, hitTarget, this.getAttackKnockback());
                    }
                }
            } else if(this == FLING){
                // Passed in zero for attack knockback to prevent normal attack knockback application to targets
                List<LivingEntity> hitTargets = EntityHelper.hitTargetsWithAOEAttack(attacker, attackBounds, this.getAttackDamage(), 0, false);
                for(LivingEntity hitTarget : hitTargets){
                    // Now apply the attack knockback value to targets to fling them upwards
                    EntityHelper.throwTarget(attacker, hitTarget, this.getAttackKnockback());
                }
            } else if(this == SINGLE_STOMP){
                Vec3 lateralOffset = Vec3.ZERO.add(attacker.isLeftHanded() ? 1 : -1, 0, 0).yRot(-attacker.getYHeadRot() * Mth.DEG_TO_RAD);
                attackBounds = attackBounds.move(lateralOffset);
                EntityHelper.hitTargetsWithAOEAttack(attacker, attackBounds, this.getAttackDamage(), this.getAttackKnockback(), true);
            } else if(this == DOUBLE_STOMP){
                List<LivingEntity> hitTargets = EntityHelper.hitTargetsWithAOEAttack(attacker, attackBounds, this.getAttackDamage(), this.getAttackKnockback(), true);
                for(LivingEntity hitTarget : hitTargets){
                    // desired freeze time is 15 seconds aka 300 ticks, but vanilla will decrease the freeze timer at a rate of 2 ticks for every tick outside powdered snow, so we double it
                    hitTarget.setTicksFrozen(hitTarget.getTicksFrozen() + 300 * 2);
                }
            } else{
                EntityHelper.hitTargetsWithAOEAttack(attacker, attackBounds, this.getAttackDamage(), this.getAttackKnockback(), true);
            }
        }

        @Override
        public boolean isFinished(FrostomperEntity attacker, int attackTicker) {
            if(this == CHARGE){
                return !attacker.isChargingForward();
            } else{
                return attackTicker >= this.getAttackDuration();
            }
        }

        public static FrostomperAttackType byOrdinal(int pOrdinal) {
            if (pOrdinal < 0 || pOrdinal > values().length) {
                pOrdinal = 0;
            }

            return values()[pOrdinal];
        }
    }
}
