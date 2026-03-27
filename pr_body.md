## ⚡ Refactor Thread.sleep to async scheduling in CloudSyncManager

**💡 What:**
Refactored the initial `CloudSyncManager` registration logic to use a recursive method `attemptRegistration` instead of an iterative `for` loop combined with `Thread.sleep(15_000)`. When registration fails, the next attempt is scheduled using `Bukkit.getScheduler().runTaskLaterAsynchronously` with a 300-tick delay.

**🎯 Why:**
Sleeping in an asynchronous task (`CompletableFuture.runAsync`) ties up a thread from the common `ForkJoinPool` or whichever thread pool it runs in for the full duration of the sleep (up to 75 seconds if 5 retries fail). In a heavily loaded Bukkit server, thread pool starvation can cause major performance issues and latency spikes, as well as delays for other tasks needing async execution. By scheduling the retry recursively, the thread is immediately freed up for other work and only re-acquired when the scheduled tick delay (15 seconds) elapses.

**📊 Measured Improvement:**
Due to the nature of this change—which addresses asynchronous thread pool exhaustion—a traditional microbenchmark focusing on CPU time or memory allocations would not capture the true benefit. Instead, the performance improvement is architectural: a blocked thread is now immediately yielded back to the pool, preventing potential task starvation across the server during network outages or Render cold starts. This ensures that the overall server latency and task processing rate remain stable.
