/*
 ============================================================================
 Name        : hev-blocklist.c
 Description : Domain blocklist / allowlist for the mapped DNS resolver (ProxyX)

 Design notes:
  - Two string sets (blocklist, allowlist), each an open-addressing hash table
    sized once at load time (no rehash on the hot path).
  - Entries are stored as pointers into the file buffer we keep alive, so there
    is no per-entry allocation.
  - Lookups are label-boundary suffix matches; the allowlist takes precedence.
  - Everything is loaded once (before DNS handling starts) and read-only after,
    so no locking is needed on the single DNS task thread. Fail-open: if a file
    is missing or empty, that set is empty and nothing is blocked.
 ============================================================================
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "hev-blocklist.h"

typedef struct
{
    char **slots;     /* open-addressing table; a NULL slot means empty */
    unsigned int cap; /* power of two */
    unsigned int count;
} StrSet;

static StrSet g_block;
static StrSet g_allow;
static char *g_block_buf;
static char *g_allow_buf;
static int g_loaded;

static unsigned int
fnv1a (const char *s)
{
    unsigned int h = 2166136261u;
    while (*s) {
        h ^= (unsigned char)*s++;
        h *= 16777619u;
    }
    return h;
}

static void
set_init (StrSet *s, unsigned int cap)
{
    s->count = 0;
    s->cap = cap;
    s->slots = (char **)calloc (cap, sizeof (char *));
    if (!s->slots)
        s->cap = 0;
}

static void
set_free (StrSet *s)
{
    if (s->slots)
        free (s->slots);
    s->slots = NULL;
    s->cap = 0;
    s->count = 0;
}

static void
set_insert (StrSet *s, char *key)
{
    unsigned int mask, i;

    if (!s->slots || s->cap == 0)
        return;
    /* keep the table under ~75% full for fast probing (it is pre-sized) */
    if ((s->count + 1) * 4 >= s->cap * 3)
        return;

    mask = s->cap - 1;
    i = fnv1a (key) & mask;
    while (s->slots[i]) {
        if (strcmp (s->slots[i], key) == 0)
            return;
        i = (i + 1) & mask;
    }
    s->slots[i] = key;
    s->count++;
}

static int
set_has (StrSet *s, const char *key)
{
    unsigned int mask, i;

    if (!s->slots || s->cap == 0)
        return 0;

    mask = s->cap - 1;
    i = fnv1a (key) & mask;
    while (s->slots[i]) {
        if (strcmp (s->slots[i], key) == 0)
            return 1;
        i = (i + 1) & mask;
    }
    return 0;
}

/* 1 if name, or any parent domain at a label boundary, is in the set. */
static int
set_has_suffix (StrSet *s, const char *name)
{
    const char *p = name;

    while (p && *p) {
        const char *dot;

        if (set_has (s, p))
            return 1;
        dot = strchr (p, '.');
        if (!dot)
            break;
        p = dot + 1;
    }
    return 0;
}

static char *
read_file (const char *path)
{
    FILE *f;
    long n, rd;
    char *buf;

    if (!path || !path[0])
        return NULL;
    f = fopen (path, "rb");
    if (!f)
        return NULL;
    if (fseek (f, 0, SEEK_END) != 0) {
        fclose (f);
        return NULL;
    }
    n = ftell (f);
    if (n <= 0) {
        fclose (f);
        return NULL;
    }
    if (fseek (f, 0, SEEK_SET) != 0) {
        fclose (f);
        return NULL;
    }
    buf = (char *)malloc ((size_t)n + 1);
    if (!buf) {
        fclose (f);
        return NULL;
    }
    rd = (long)fread (buf, 1, (size_t)n, f);
    fclose (f);
    if (rd < 0)
        rd = 0;
    buf[rd] = '\0';
    return buf;
}

static int
is_ip_token (const char *t)
{
    const char *p = t;
    int has_digit = 0;

    if (strchr (t, ':'))
        return 1; /* any IPv6-ish token */
    while (*p) {
        if (*p >= '0' && *p <= '9')
            has_digit = 1;
        else if (*p != '.')
            return 0;
        p++;
    }
    return has_digit;
}

static void
normalize (char *s)
{
    size_t n;
    char *p = s;

    for (; *p; p++) {
        if (*p >= 'A' && *p <= 'Z')
            *p = (char)(*p + 32);
    }
    n = (size_t)(p - s);
    while (n > 0 && s[n - 1] == '.') {
        s[n - 1] = '\0';
        n--;
    }
}

static void
process_line (char *line, StrSet *set)
{
    char *domain;
    char *sp;

    while (*line == ' ' || *line == '\t')
        line++;
    if (*line == '\0' || *line == '#')
        return;

    /* first whitespace-separated token */
    sp = line;
    while (*sp && *sp != ' ' && *sp != '\t')
        sp++;

    if (*sp) {
        *sp = '\0';
        if (is_ip_token (line)) {
            /* hosts format: "IP domain" -> take the second token */
            char *t2 = sp + 1;
            char *sp2;
            while (*t2 == ' ' || *t2 == '\t')
                t2++;
            sp2 = t2;
            while (*sp2 && *sp2 != ' ' && *sp2 != '\t')
                sp2++;
            *sp2 = '\0';
            domain = t2;
        } else {
            domain = line;
        }
    } else {
        domain = line;
    }

    if (*domain == '\0' || *domain == '#')
        return;
    normalize (domain);
    if (!strchr (domain, '.'))
        return; /* skip entries without a dot, e.g. "localhost" */
    set_insert (set, domain);
}

static void
load_into (const char *path, StrSet *set, char **out_buf)
{
    char *buf, *p, *end;
    unsigned int lines, cap;
    long i, len;

    buf = read_file (path);
    *out_buf = buf;
    if (!buf) {
        set_init (set, 16);
        return;
    }

    len = (long)strlen (buf);
    lines = 1;
    for (i = 0; i < len; i++)
        if (buf[i] == '\n')
            lines++;

    cap = 16;
    while (cap < lines * 2u)
        cap <<= 1;
    set_init (set, cap);

    p = buf;
    end = buf + len;
    while (p < end) {
        char *nl = p;
        while (nl < end && *nl != '\n' && *nl != '\r')
            nl++;
        *nl = '\0';
        process_line (p, set);
        p = nl + 1;
    }
}

void
hev_blocklist_load (const char *block_path, const char *allow_path)
{
    hev_blocklist_unload ();
    load_into (block_path, &g_block, &g_block_buf);
    load_into (allow_path, &g_allow, &g_allow_buf);
    g_loaded = 1;
}

int
hev_blocklist_is_blocked (const char *name)
{
    char buf[256];
    size_t i;

    if (!g_loaded || !name || !name[0])
        return 0;

    for (i = 0; name[i] && i < sizeof (buf) - 1; i++) {
        char c = name[i];
        if (c >= 'A' && c <= 'Z')
            c = (char)(c + 32);
        buf[i] = c;
    }
    buf[i] = '\0';
    if (i > 0 && buf[i - 1] == '.')
        buf[i - 1] = '\0';

    if (set_has_suffix (&g_allow, buf))
        return 0;
    if (set_has_suffix (&g_block, buf))
        return 1;
    return 0;
}

void
hev_blocklist_unload (void)
{
    set_free (&g_block);
    set_free (&g_allow);
    if (g_block_buf) {
        free (g_block_buf);
        g_block_buf = NULL;
    }
    if (g_allow_buf) {
        free (g_allow_buf);
        g_allow_buf = NULL;
    }
    g_loaded = 0;
}