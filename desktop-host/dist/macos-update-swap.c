#include <stdio.h>
#include <errno.h>

/* The two complete bundles must be siblings on the same macOS filesystem. */
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    if (renamex_np(argv[1], argv[2], RENAME_SWAP) != 0) {
        perror("QPlayer bundle swap");
        return 1;
    }
    return 0;
}
