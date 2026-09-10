// Compatibility gate only: saves CUPS input, never opens Bluetooth.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <errno.h>

int main(int argc, char **argv) {
    if (argc == 1) {
        puts("direct x6driverdiag \"X6\" \"X6 diagnostic receiver\"");
        return 0;
    }
    if (argc < 6 || argc > 7) return 4;
    const char *dir = "/private/var/spool/x6driver-diagnostic";
    char name[256];
    snprintf(name, sizeof(name), "%s/job-XXXXXX", dir);
    if (!mkdtemp(name)) { perror("ERROR: diagnostic directory"); return 4; }
    char path[512];
    snprintf(path, sizeof(path), "%s/options.txt", name);
    FILE *meta = fopen(path, "w");
    if (!meta) return 4;
    fprintf(meta, "job=%s\nuser=%s\ntitle=%s\ncopies=%s\noptions=%s\ncontent-type=%s\n", argv[1],argv[2],argv[3],argv[4],argv[5],getenv("CONTENT_TYPE")?getenv("CONTENT_TYPE"):"");
    fclose(meta);
    snprintf(path, sizeof(path), "%s/input.pdf", name);
    int out = open(path, O_WRONLY|O_CREAT|O_EXCL, 0600);
    int in = argc == 7 ? open(argv[6], O_RDONLY) : STDIN_FILENO;
    if (in < 0 || out < 0) return 4;
    char buffer[65536]; ssize_t n;
    while ((n=read(in, buffer, sizeof(buffer))) > 0) {
        for (ssize_t pos=0; pos<n;) {
            ssize_t written=write(out, buffer+pos, n-pos);
            if (written<0) { if(errno==EINTR)continue; return 4; }
            pos+=written;
        }
    }
    fsync(out); close(out); if(argc==7)close(in);
    if(n<0)return 4;
    fprintf(stderr,"INFO: Diagnostic PDF captured; no physical printing.\n");
    return 0;
}
