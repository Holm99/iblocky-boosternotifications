package net.holm.boosternoti;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.client.network.PlayerListEntry;
import com.mojang.authlib.GameProfile;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class iBlockyBoosterNotificationClient implements ClientModInitializer {
    private static KeyBinding boosterKeyBinding;
    private static KeyBinding toggleHudKeyBinding;
    private static KeyBinding showPlayerListKeyBinding; // Keybind for player list
    private static KeyBinding toggleInstructionsKeyBinding;
    private static KeyBinding testPickaxeDataKeyBinding;
    private static KeyBinding enchantLeftKeyBinding; // New key bindings for EnchantHUD
    private static KeyBinding enchantRightKeyBinding;
    private static BoosterConfig config;
    static BoosterStatusWindow boosterStatusWindow;
    private static boolean isHudVisible = true;
    private static CustomPlayerList customPlayerList;  // Custom player list instance
    private static boolean showPlayerList = false;  // Flag for toggling player list visibility
    private static EnchantHUD enchantHUD;
    private static SaleSummaryManager saleSummaryManager;
    private boolean gameModeChecked = false;

    private static final Pattern TOKEN_BOOSTER_PATTERN = Pattern.compile(
            "\\s-\\sTokens\\s\\((\\d+(\\.\\d+)?)x\\)\\s\\((\\d+d\\s)?(\\d+h\\s)?(\\d+m\\s)?(\\d+s\\s)?remaining\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RICH_BOOSTER_PATTERN = Pattern.compile("iBlocky → Your Rich pet has rewarded you with a 2x sell booster for the next (\\d+d\\s)?(\\d+h\\s)?(\\d+m\\s)?(\\d+s)?!");
    private static final Pattern PURCHASED_LEVELS_PATTERN = Pattern.compile("Purchased (\\d+) levels of ([A-Za-z ]+)");
    private static final Pattern LEVELED_UP_PATTERN = Pattern.compile("You leveled up ([A-Za-z ]+) to level (\\d+) for ([\\d,.]+) tokens!");
    private boolean hudInitialized = false; // Add a flag to check if the HUD has already been initialized

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final long BOOSTER_COMMAND_INTERVAL = 20 * 60; // 20 minutes in seconds
    private final ScheduledExecutorService boosterScheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> boosterTask;
    private final ScheduledExecutorService refreshScheduler = Executors.newScheduledThreadPool(1);  // Renamed to avoid conflict
    private ScheduledFuture<?> refreshTask;

    private static iBlockyBoosterNotificationClient instance;

    static final Map<String, String> availableEnchants = new HashMap<>();
    static {
        availableEnchants.put("Locksmith", "Locksmith");
        availableEnchants.put("Jurassic", "Jurassic");
        availableEnchants.put("Terminator", "Terminator");
        availableEnchants.put("Efficiency", "Efficiency");
        availableEnchants.put("Explosive", "Explosive");
        availableEnchants.put("Greed", "Greed");
        availableEnchants.put("Drill", "Drill");
        availableEnchants.put("Profit", "Profit");
        availableEnchants.put("Multiplier", "Multiplier");
        availableEnchants.put("Spelunker", "Spelunker");
        availableEnchants.put("Spirit", "Spirit");
        availableEnchants.put("Vein Miner", "Vein Miner");
        availableEnchants.put("Cubed", "Cubed");
        availableEnchants.put("Jackhammer", "Jackhammer");
        availableEnchants.put("Stellar Sight", "Stellar Sight");
        availableEnchants.put("Speed", "Speed");
        availableEnchants.put("Starstruck", "Starstruck");
        availableEnchants.put("Blackhole", "Blackhole");
        availableEnchants.put("Lucky", "Lucky");
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = BoosterConfig.load();

        // Ensure key bindings are registered early
        registerKeyBindings();

        boosterStatusWindow = new BoosterStatusWindow(config, boosterKeyBinding, toggleHudKeyBinding, showPlayerListKeyBinding, toggleInstructionsKeyBinding);
        customPlayerList = new CustomPlayerList();

        registerJoinEvent();
        HubCommand.register();

        // Register the HUD rendering after key bindings are set
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (showPlayerList) {
                customPlayerList.renderPlayerList(drawContext);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            RankSetCommand.register(dispatcher);
            SaleSummaryCommand.register(dispatcher);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Only proceed if the player is on the correct server
            if (isCorrectServer() && !gameModeChecked) {
                customPlayerList.refreshPlayerList();
                customPlayerList.detectGameMode();

                if (isCorrectGameMode()) {
                    if (!hudInitialized) {
                        System.out.println("Correct game mode detected, initializing HUD.");
                        initializeHUD();
                    }
                } else {
                    if (hudInitialized) {
                        System.out.println("Switching to unsupported game mode, hiding HUD.");
                        resetHUD();
                    }
                }

                gameModeChecked = true;
            }
        });
    }

    private void runPickaxeDataFetcher() {
        // Call the method to fetch and read pickaxe data
        PickaxeDataFetcher.readPickaxeComponentData();

        // You can add additional checks or processing here if needed
        // The component data will be printed by the method itself
    }

    public void registerJoinEvent() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Load config if not already loaded
            if (config == null) {
                config = BoosterConfig.load();
            }

            // Add a short delay to ensure players are loaded
            scheduler.schedule(() -> {
                if (isCorrectServer()) {
                    System.out.println("Connected to the correct server, checking for game mode.");

                    customPlayerList.refreshPlayerList(); // Ensure player list is updated
                    customPlayerList.detectGameMode(); // Detect the game mode

                    if (isCorrectGameMode()) {
                        if (!hudInitialized) {
                            initializeHUD();  // Separated HUD initialization logic
                        } else {
                            System.out.println("HUD is already initialized, skipping re-initialization.");
                            setHudVisible(true);  // Only make it visible if it's already initialized.
                        }
                    } else {
                        if (hudInitialized) {
                            System.out.println("Switching to unsupported game mode, hiding HUD.");
                            setHudVisible(false);  // Only hide it, don't reset or unregister callbacks.
                        }
                    }
                    gameModeChecked = true;  // Mark game mode as checked after joining
                } else {
                    System.out.println("Mod is not initialized: Connected to a non-supported server.");
                    setHudVisible(false); // Hide HUD when not on a supported server, don't reset.
                }
            }, 2, TimeUnit.SECONDS); // Delay of 2 seconds (adjust as necessary)
        });
    }

    private void initializeHUD() {
        System.out.println("HUD is being initialized for the first time.");

        if (!hudInitialized) {
            hudInitialized = true;
            setHudVisible(true);

            // Register HudRenderCallback if it's not already initialized
            HudRenderCallback.EVENT.register(boosterStatusWindow);

            // Initialize and register EnchantHUD
            enchantHUD = new EnchantHUD(); // Proper initialization of EnchantHUD
            HudRenderCallback.EVENT.register(enchantHUD); // Register HUD rendering for EnchantHUD

            // Initialize other components
            PickaxeDataFetcher.readPickaxeComponentData();
            saleSummaryManager = new SaleSummaryManager();
            BackpackSpaceTracker.init();
            registerMessageListeners();
            registerMouseEvents();
            registerLogoutEvent();
            registerBoosterScheduler();

            // Handle closing the booster window when the screen is closed
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (MinecraftClient.getInstance().currentScreen == null) {
                    boosterStatusWindow.handleScreenClose();
                }
            });
        }
    }

    private void resetHUD() {
        System.out.println("Resetting HUD: Hiding components but not reinitializing.");
        setHudVisible(false);
        showPlayerList = false;
    }

    private void registerLogoutEvent() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Clear booster info and hide HUD when disconnected
            boosterStatusWindow.clearBoosterInfo();
            setHudVisible(false);  // Hide the HUD upon disconnecting
            showPlayerList = false; // Reset player list visibility as well
        });
    }

    public static SaleSummaryManager getSaleSummaryManager() {
        return saleSummaryManager;
    }

    private void registerBoosterScheduler() {
        if (boosterTask != null && !boosterTask.isCancelled()) {
            boosterTask.cancel(false);
        }

        boosterTask = boosterScheduler.scheduleAtFixedRate(() -> {
            if (isHudVisible) {
                sendBoosterCommand();
                fetchAndUpdateBalance();
                fetchAndLogPlayerPrefix();
            }
        }, 0, BOOSTER_COMMAND_INTERVAL, TimeUnit.SECONDS);
    }

    static boolean isCorrectServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getCurrentServerEntry() != null) {
            String serverAddress = client.getCurrentServerEntry().address;
            return "play.iblocky.net".equalsIgnoreCase(serverAddress) || "mc.iblocky.net".equalsIgnoreCase(serverAddress);
        }
        return false;
    }

    public boolean isCorrectGameMode() {
        return "Prison".equals(customPlayerList.getCurrentGameMode());  // Check if the current game mode is "Prison"
    }

    public void fetchAndUpdateBalance() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.networkHandler.sendChatCommand("balance");
        }
    }

    void startFetchingDisplayName() {
        scheduler.scheduleAtFixedRate(this::fetchAndLogPlayerPrefix, config.initialFetchDelaySeconds, config.fetchIntervalSeconds, TimeUnit.SECONDS);
    }

    public static iBlockyBoosterNotificationClient getInstance() {
        return instance;
    }

    void fetchAndLogPlayerPrefix() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!isCorrectServer() && isCorrectGameMode()) {
            return;
        }

        if (client.player != null) {
            GameProfile playerProfile = client.player.getGameProfile();
            UUID playerUUID = playerProfile.getId();

            BoosterConfig config = BoosterConfig.load();
            String manualRank = config.getManualRank(playerUUID);

            if (manualRank != null) {
                SellBoostCalculator.setRank(manualRank);
                return;
            }

            if (client.getNetworkHandler() == null || client.getNetworkHandler().getPlayerList().isEmpty()) {
                return;
            }

            for (PlayerListEntry entry : Objects.requireNonNull(client.getNetworkHandler()).getPlayerList()) {
                if (entry.getProfile().getId().equals(playerProfile.getId())) {
                    if (entry.getScoreboardTeam() != null) {
                        String teamPrefix = entry.getScoreboardTeam().getPrefix().getString().trim();
                        SellBoostCalculator.setRank(teamPrefix);
                    }
                    break;
                }
            }
        }
    }

    private void registerMessageListeners() {
        ClientReceiveMessageEvents.CHAT.register((Text message, SignedMessage signedMessage, GameProfile sender, MessageType.Parameters params, Instant receptionTimestamp) -> {
            String msg = message.getString();
            processChatMessage(msg, false);
        });

        ClientReceiveMessageEvents.GAME.register((Text message, boolean overlay) -> {
            String msg = message.getString();
            processChatMessage(msg, true);
        });
    }

    private void processChatMessage(String msg, boolean ignoredIsGameMessage) {
        if (msg.contains("§6§lBackpack Space §f§l→")) {
            return;
        }

        // Token booster detection
        Matcher matcher = TOKEN_BOOSTER_PATTERN.matcher(msg);
        if (matcher.find()) {
            String multiplier = matcher.group(1);
            StringBuilder remaining = new StringBuilder();
            for (int i = 3; i <= 6; i++) {
                if (matcher.group(i) != null) {
                    remaining.append(matcher.group(i));
                }
            }
            if (!remaining.isEmpty()) {
                boosterStatusWindow.setTokensBoosterActive(true, multiplier.trim(), remaining.toString().replace("remaining", "").trim());
            }
        }

        // Rich booster detection
        Matcher richMatcher = RICH_BOOSTER_PATTERN.matcher(msg);
        if (richMatcher.find()) {
            StringBuilder remaining = new StringBuilder();
            for (int i = 1; i <= 4; i++) {
                if (richMatcher.group(i) != null) remaining.append(richMatcher.group(i));
            }

            if (!remaining.isEmpty()) {
                boosterStatusWindow.setRichBoosterActive(true, remaining.toString().trim());
            }
        }

        // Token balance detection
        if (msg.startsWith("Token Balance:")) {
            String balanceString = msg.substring("Token Balance:".length()).replace(",", "").trim();
            try {
                double balance = Double.parseDouble(balanceString);
                boosterStatusWindow.setTokenBalance(balance);
            } catch (NumberFormatException e) {
                System.err.println("Failed to parse token balance: " + balanceString);
            }
        }

        // Sale summary detection
        if (msg.startsWith("§f§l(!) §e§lSALE §6§lSUMMARY")) {
            int startIndex = msg.indexOf("§fTotal: §6") + "§fTotal: §6".length();
            int endIndex = msg.indexOf("Tokens", startIndex);
            if (startIndex < "§fTotal: §6".length() || endIndex == -1) {
                System.err.println("Failed to locate total tokens in the message.");
                return;
            }
            String totalString = msg.substring(startIndex, endIndex).replace("§6", "").replace(",", "").trim();

            try {
                long tokens = Long.parseLong(totalString);
                SaleSummaryManager saleSummaryManager = iBlockyBoosterNotificationClient.getSaleSummaryManager();
                saleSummaryManager.addSale(tokens);
                boosterStatusWindow.setTotalSales(saleSummaryManager.getTotalSales());
                fetchAndUpdateBalance();
            } catch (NumberFormatException e) {
                System.err.println("Failed to parse token value from message: " + totalString);
            }
        }

        // Purchased levels of an enchant detection
        Matcher purchaseMatcher = PURCHASED_LEVELS_PATTERN.matcher(msg);
        if (purchaseMatcher.find()) {
            String amount = purchaseMatcher.group(1);
            String enchantName = purchaseMatcher.group(2).trim();

            if (availableEnchants.containsKey(enchantName)) {
                fetchAndUpdateBalance();
            }
        }

        // Leveled up enchant detection
        Matcher levelUpMatcher = LEVELED_UP_PATTERN.matcher(msg);
        if (levelUpMatcher.find()) {
            String enchantName = levelUpMatcher.group(1).trim();
            String amount = levelUpMatcher.group(2).trim();
            fetchAndUpdateBalance();
            PickaxeDataFetcher.readPickaxeComponentData();
        }
    }

    private void registerMouseEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (MinecraftClient.getInstance().currentScreen != null) {
                double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
                double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();

                if (GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS) {
                    boosterStatusWindow.handleMousePress(mouseX, mouseY);
                } else if (GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_RELEASE) {
                    boosterStatusWindow.handleMouseRelease(mouseX, mouseY);
                }

                boosterStatusWindow.onMouseMove(mouseX, mouseY);  // Always track mouse move
            }
        });
    }

    private void registerKeyBindings() {
        boosterKeyBinding = new KeyBinding("key.boosternoti.booster", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.boosternoti.general");
        toggleHudKeyBinding = new KeyBinding("key.boosternoti.toggleHud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.boosternoti.general");
        showPlayerListKeyBinding = new KeyBinding("key.boosternoti.showPlayerList", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.boosternoti.general");
        toggleInstructionsKeyBinding = new KeyBinding("key.boosternoti.toggleInstructions", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.boosternoti.general");
        testPickaxeDataKeyBinding = new KeyBinding("key.iblockybooster.testPickaxeData", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.boosternoti.general");
        enchantLeftKeyBinding = new KeyBinding("key.enchant_hud.left", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, "category.enchant_hud");
        enchantRightKeyBinding = new KeyBinding("key.enchant_hud.right", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, "category.enchant_hud");

        KeyBindingHelper.registerKeyBinding(boosterKeyBinding);
        KeyBindingHelper.registerKeyBinding(toggleHudKeyBinding);
        KeyBindingHelper.registerKeyBinding(showPlayerListKeyBinding);
        KeyBindingHelper.registerKeyBinding(toggleInstructionsKeyBinding);
        KeyBindingHelper.registerKeyBinding(testPickaxeDataKeyBinding);
        KeyBindingHelper.registerKeyBinding(enchantLeftKeyBinding);
        KeyBindingHelper.registerKeyBinding(enchantRightKeyBinding);

        // Handle key events during the client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (boosterKeyBinding.wasPressed() && isCorrectServer() && isCorrectGameMode()) {
                sendBoosterCommand();
                fetchAndLogPlayerPrefix();
                fetchAndUpdateBalance();
            }

            if (toggleHudKeyBinding.wasPressed() && isCorrectServer() && isCorrectGameMode()) {
                toggleHudVisibility(!isHudVisible);
                fetchAndUpdateBalance();
            }

            if (showPlayerListKeyBinding.isPressed() && isCorrectServer()) {
                if (!showPlayerList) {
                    startRefreshingPlayerList();
                    showPlayerList = true;
                }
            } else {
                if (showPlayerList) {
                    stopRefreshingPlayerList();
                    showPlayerList = false;
                }
            }

            if (enchantLeftKeyBinding.wasPressed() && isCorrectServer() && isCorrectGameMode()) {
                enchantHUD.cycleLeft();  // Cycle left through enchants
            }

            if (enchantRightKeyBinding.wasPressed() && isCorrectServer() && isCorrectGameMode()) {
                enchantHUD.cycleRight();  // Cycle right through enchants
            }

            if (toggleInstructionsKeyBinding.wasPressed() && isCorrectServer() && isCorrectGameMode()) {
                boosterStatusWindow.toggleInstructions();
            }

            if (testPickaxeDataKeyBinding.wasPressed() && isCorrectServer() && isCorrectGameMode()) {
                System.out.println("Pickaxe Data Fetcher key pressed.");
                runPickaxeDataFetcher();
            }
        });
    }

    private void startRefreshingPlayerList() {
        if (refreshTask == null || refreshTask.isCancelled()) {
            // Refresh the player list every second while the key is held
            refreshTask = refreshScheduler.scheduleAtFixedRate(() -> {
                if (showPlayerList) {
                    customPlayerList.refreshPlayerList();  // Refresh the player list

                    // Check and rectify the game mode if necessary
                    if (!customPlayerList.isGameModeDetected() || customPlayerList.isGameModeChanged()) {
                        customPlayerList.detectGameMode();  // Re-run game mode detection if changed
                    }

                    System.out.println("Player list is being refreshed with game mode: " + customPlayerList.getCurrentGameMode());
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    private void stopRefreshingPlayerList() {
        if (refreshTask != null && !refreshTask.isCancelled()) {
            refreshTask.cancel(false);  // Stop refreshing the player list when the key is released
        }
    }

    public static void toggleHudVisibility(boolean visible) {
        isHudVisible = visible;
        setHudVisible(isHudVisible);
    }

    public static void setHudVisible(boolean visible) {
        isHudVisible = visible;
    }

    public static boolean isHudVisible() {
        return !isHudVisible;
    }

    private void sendBoosterCommand() {
        if (MinecraftClient.getInstance().player != null) {
            MinecraftClient.getInstance().player.networkHandler.sendChatCommand("booster");
        }
    }

    public static void startFetchingDisplayNameFromInstance() {
        if (instance != null) {
            instance.startFetchingDisplayName();
        }
    }
}
