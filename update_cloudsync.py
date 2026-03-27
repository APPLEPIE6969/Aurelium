import re

with open('src/main/java/com/aureleconomy/web/CloudSyncManager.java', 'r') as f:
    content = f.read()

start_pattern = r'CompletableFuture\.runAsync\(\(\) -> \{\s*for \(int attempt = 1; attempt <= 5; attempt\+\+\) \{\s*try \{\s*plugin\.getComponentLogger\(\)\.info\("Cloud dashboard: registering \(attempt " \+ attempt \+ "/5\)\.\.\."\);\s*register\(\);\s*registered = true;\s*plugin\.getComponentLogger\(\)\.info\("Cloud dashboard registered — server ID: " \+ serverId\);\s*// Do an initial sync immediately\s*try \{\s*syncMarketData\(\);\s*\} catch \(Exception ignored\) \{\s*\}\s*return;\s*\} catch \(Exception e\) \{\s*plugin\.getComponentLogger\(\)\.warn\("Registration attempt " \+ attempt \+ " failed: " \+ e\.getMessage\(\)\);\s*if \(attempt < 5\) \{\s*try \{\s*Thread\.sleep\(15_000\);\s*\} catch \(InterruptedException ignored\) \{\s*return;\s*\}\s*\}\s*\}\s*\}\s*plugin\.getComponentLogger\(\)\.error\("Failed to register with cloud dashboard after 5 attempts at " \+ baseUrl\);\s*\}\);'

replacement = 'attemptRegistration(1);'

new_content = re.sub(start_pattern, replacement, content)

new_method = """
    private void attemptRegistration(int attempt) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getComponentLogger().info("Cloud dashboard: registering (attempt " + attempt + "/5)...");
                register();
                registered = true;
                plugin.getComponentLogger().info("Cloud dashboard registered — server ID: " + serverId);
                // Do an initial sync immediately
                try {
                    syncMarketData();
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Registration attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < 5) {
                    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> attemptRegistration(attempt + 1), 300L);
                } else {
                    plugin.getComponentLogger().error("Failed to register with cloud dashboard after 5 attempts at " + baseUrl);
                }
            }
        });
    }

    public void stop() {"""

new_content = new_content.replace('    public void stop() {', new_method)

with open('src/main/java/com/aureleconomy/web/CloudSyncManager.java', 'w') as f:
    f.write(new_content)
