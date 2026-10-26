Write Java that a colleague can read and change safely. Each rule has a short reason; follow the
rule unless the reason clearly doesn't apply.

1. **Instances over static.** Put behavior in classes you instantiate; give collaborators to the
   constructor (dependency injection), keep them in `private final` fields. Static is for constants
   and pure helper functions without state or I/O (`Math.max`-like). Why: static state and static
   calls to other services can't be replaced in tests and hide what a class depends on.
   Bad: `static List<User> users = ...; static void save(User u) { Db.insert(u); }`
   Good: `class UserService { private final UserRepository repo; UserService(UserRepository repo) { this.repo = repo; } }`

2. **No mutable global state, no hand-made singletons.** No `static` mutable fields, no
   `getInstance()`. Create one instance at startup (in `main` or a small wiring class) and pass it on.

3. **One job per class, small methods.** A class should be describable in one sentence without
   "and". Split methods longer than about 30 lines or with more than two levels of nesting into named
   steps. Prefer several focused classes over one "Manager"/"Utils"/"Helper" class.

4. **Immutable by default.** Use `record` for data carriers, `final` fields, `List.copyOf` /
   `Map.copyOf` for collections passed in or out. Mutate only where it's the point of the class.

5. **Clear names.** Classes are nouns for what they are (`InvoiceRepository`, not `InvoiceHelper`);
   methods are verbs for what they do (`calculateTotal`); booleans read as questions (`isEmpty`).
   No abbreviations except well-known ones (`id`, `url`). Names say what, not how.

6. **Small public surface.** Make classes, constructors and methods as private as possible; expose
   an interface only where there are (or will soon be) several implementations or a test double is
   needed. Don't add getters/setters for every field.

7. **Errors.** Validate arguments at public boundaries (`Objects.requireNonNull`,
   `IllegalArgumentException` with a message saying what was wrong). Never swallow exceptions
   silently; catch only what you can handle, otherwise let it propagate. Use `Optional` for "may be
   absent" return values, not for fields or parameters; don't return `null` collections (return an
   empty one).

8. **Resources.** Open files, streams, connections and HTTP clients with try-with-resources.

9. **Prefer the standard library and modern Java.** Records, `switch` expressions, `var` where the
   type is obvious, streams for simple transformations (a loop when it's clearer), `java.time` for
   dates, `java.nio.file.Files` / `Path` for files.

10. **Composition over inheritance.** Extend a class only for a real "is a" relationship you
    control; otherwise hold the other object in a field and delegate.

11. **No magic values.** Name repeated or meaningful literals as constants
    (`private static final Duration TIMEOUT = Duration.ofSeconds(30)`); read configuration once at
    startup and pass it in.

12. **Testable by design.** If a class is hard to test (needs the clock, the network, the file
    system, random numbers), inject that dependency (`Clock`, an interface, a `Path`) instead of
    reaching for it statically.

13. **Packages by feature.** Group classes by what they belong to (`orders`, `billing`), not by
    kind (`controllers`, `services`, `models`), unless the project already does otherwise.

14. **Follow the project.** Existing conventions in the code (naming, layout, frameworks, test
    style) win over these rules; don't restyle code the task doesn't touch.

15. **Comments explain why.** Javadoc on public types and non-obvious methods; comments for the
    reason behind a decision, not a retelling of the code. Delete commented-out code.
