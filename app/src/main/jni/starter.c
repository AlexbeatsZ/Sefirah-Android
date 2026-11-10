#include "misc.h"

#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <linux/capability.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#define perrorf(...) fprintf(stderr, __VA_ARGS__)

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_PM_PATH 7
#define EXIT_FATAL_KILL 9

#define SERVER_NAME "sefirah_worker"
#define ROOT_SERVER_NAME "sefirah_root_clipboard"
#define SERVER_CLASS_PATH "sefirah.worker.WorkerService"
#define SHELL_ID 2000
#define ROOT_TOKEN_PREFIX "--root-token="

static void run_server(const char *apk_path, const char *main_class, const char *process_name,
                       const char *root_token) {
    if (setenv("CLASSPATH", apk_path, 1) != 0) {
        perrorf("fatal: can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

    char class_path[PATH_MAX];
    snprintf(class_path, sizeof(class_path), "-Djava.class.path=%s", apk_path);

    char nice_name[256];
    snprintf(nice_name, sizeof(nice_name), "--nice-name=%s", process_name);

    char *args[] = {
            "/system/bin/app_process",
            class_path,
            "/system/bin",
            nice_name,
            (char *) main_class,
            (char *) root_token,
            NULL,
    };

    execvp(args[0], args);
    perrorf("fatal: exec app_process failed\n");
    exit(EXIT_FATAL_APP_PROCESS);
}

static void start_server(const char *path, const char *main_class, const char *process_name,
                         const char *root_token) {
    int fds[2];
    if (pipe(fds) < 0) {
        perrorf("fatal: can't create pipe\n");
        exit(EXIT_FATAL_FORK);
    }

    pid_t pid = fork();
    switch (pid) {
        case -1: {
            perrorf("fatal: can't fork\n");
            exit(EXIT_FATAL_FORK);
        }
        case 0: {
            close(fds[0]);
            setsid();
            chdir("/");
            int fd = open("/dev/null", O_RDWR);
            if (fd != -1) {
                dup2(fd, STDIN_FILENO);
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                if (fd > 2)
                    close(fd);
            }
            char ready = 1;
            write(fds[1], &ready, 1);
            close(fds[1]);
            run_server(path, main_class, process_name, root_token);
        }
        default: {
            close(fds[1]);
            char ready;
            read(fds[0], &ready, 1);
            close(fds[0]);
            exit(EXIT_SUCCESS);
        }
    }
}

static void kill_old_server_cb(pid_t pid) {
    if (pid == getpid())
        return;

    char name[1024];
    if (get_proc_name(pid, name, sizeof(name)) != 0)
        return;
    if (strcmp(name, SERVER_NAME) != 0)
        return;

    if (kill(pid, SIGKILL) == 0)
        printf("info: killed %d (%s)\n", pid, name);
    else if (errno == EPERM) {
        perrorf("fatal: can't kill %d, stop the existing worker first\n", pid);
        exit(EXIT_FATAL_KILL);
    } else {
        printf("warn: failed to kill %d (%s)\n", pid, name);
    }
}

static void drop_to_shell(void) {
    struct __user_cap_header_struct caps = { _LINUX_CAPABILITY_VERSION_3, 0 };
    struct __user_cap_data_struct empty[2] = { {0}, {0} };

    // Do this before opening the APK or starting ART. Fail closed if the su profile disallows it.
    if (geteuid() != 0 || setgroups(0, NULL) != 0 ||
        setresgid(SHELL_ID, SHELL_ID, SHELL_ID) != 0 ||
        setresuid(SHELL_ID, SHELL_ID, SHELL_ID) != 0 ||
        syscall(__NR_capset, &caps, empty) != 0 ||
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        perrorf("fatal: cannot drop root privileges for the clipboard worker\n");
        exit(EXIT_FATAL_UID);
    }
}

int main(int argc, char *argv[]) {
    char apk_path[PATH_MAX] = {0};
    const char *root_token = NULL;
    for (int i = 1; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0)
            strncpy(apk_path, argv[i] + 6, sizeof(apk_path) - 1);
        else if (strncmp(argv[i], ROOT_TOKEN_PREFIX, sizeof(ROOT_TOKEN_PREFIX) - 1) == 0)
            root_token = argv[i];
    }

    uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        perrorf("fatal: run worker starter from root or adb shell (uid=%d)\n", uid);
        exit(EXIT_FATAL_UID);
    }

    if (root_token != NULL) {
        drop_to_shell();
    } else {
        foreach_proc(kill_old_server_cb);
    }

    if (apk_path[0] == '\0') {
        char buf[PATH_MAX];
        ssize_t len = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
        if (len != -1) {
            buf[len] = '\0';
            char *lib_pos = strstr(buf, "/lib/");
            if (lib_pos) {
                size_t prefix_len = (size_t) (lib_pos - buf);
                if (prefix_len + strlen("/base.apk") < sizeof(apk_path)) {
                    memcpy(apk_path, buf, prefix_len);
                    apk_path[prefix_len] = '\0';
                    strncat(apk_path, "/base.apk", sizeof(apk_path) - prefix_len - 1);
                }
            }
        }
    }

    if (apk_path[0] == '\0' || access(apk_path, R_OK) != 0) {
        perrorf("fatal: can't access apk %s\n", apk_path);
        exit(EXIT_FATAL_PM_PATH);
    }

    start_server(apk_path, SERVER_CLASS_PATH,
                 root_token == NULL ? SERVER_NAME : ROOT_SERVER_NAME, root_token);
}
