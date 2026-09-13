#pragma once

#include <atomic>
#include <pthread.h>

// Shared mpv state, defined in main.cpp.
extern JavaVM *g_vm;
extern mpv_handle *g_mpv;
extern std::atomic<bool> g_event_thread_request_exit;
extern pthread_t event_thread_id;
