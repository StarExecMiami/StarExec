<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@page trimDirectiveWhitespaces="true" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>

<star:template
  title="StarExecCommand CLI"
  css="public/starexeccommand, components/card, components/badge"
  js="public/starexeccommand"
>
  <main role="main" class="starexeccommand-container">
    <!-- Hero Header -->
    <header class="page-header">
      <div class="container">
        <nav class="breadcrumb" aria-label="Breadcrumb">
          <a href="${starexecRoot}/public/help.jsp" class="breadcrumb-link"
            >Support</a
          >
          <span class="breadcrumb-separator" aria-hidden="true">/</span>
          <span class="breadcrumb-current" aria-current="page">StarExecCommand</span>
        </nav>

        <h1 class="page-title">StarExecCommand</h1>
        <p class="lead">
          A powerful command-line interface for automating StarExec workflows
        </p>

        <div class="download-section">
          <a
            href="https://github.com/StarExecMiami/StarExec/releases/latest/download/starexeccommand.zip"
            class="btn-download"
            rel="external noopener"
            target="_blank"
          >
            <span class="icon" aria-hidden="true">&#8595;</span>
            <span>Download StarExecCommand</span>
          </a>
          <span class="version-info">Includes JAR and documentation &middot; <a href="https://github.com/StarExecMiami/StarExec/releases" target="_blank" rel="noopener">View all releases</a></span>
        </div>
      </div>
    </header>

    <!-- Two-column layout -->
    <div class="container">
      <div class="guide-layout">
      <!-- Sticky Navigation -->
      <aside class="sidebar">
        <nav class="doc-nav" aria-label="Documentation menu">
          <h2 class="nav-title">Contents</h2>
          <ul class="nav-list">
            <li class="nav-item">
              <a href="#getting-started" class="nav-link active"
                >Getting Started</a
              >
            </li>
            <li class="nav-item">
              <a href="#syntax" class="nav-link">Command Syntax</a>
            </li>
            <li class="nav-item">
              <a href="#parameters" class="nav-link">Parameters</a>
            </li>
            <li class="nav-item">
              <a href="#commands" class="nav-link">Commands</a>
            </li>
            <li class="nav-item">
              <a href="#modes" class="nav-link">Shell & Batch Modes</a>
            </li>
            <li class="nav-item">
              <a href="#scripting" class="nav-link">Scripting</a>
            </li>
          </ul>

          <div class="nav-footer">
            <a href="#" class="scroll-to-top" aria-label="Return to top">↑ Back to top</a>
          </div>
        </nav>
      </aside>

      <!-- Main Content -->
      <article class="documentation">
        <!-- Getting Started -->
        <section id="getting-started" class="content-section" aria-labelledby="gs-title">
          <div class="section-header">
            <h2 id="gs-title" class="section-title">
              <svg
                class="section-icon"
                width="32"
                height="32"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path
                  d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z"
                ></path>
                <path
                  d="m12 15-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2z"
                ></path>
                <path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0"></path>
                <path d="M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5"></path>
              </svg>
              Getting Started
            </h2>
            <span class="difficulty-badge difficulty-badge--beginner"
              >Beginner</span
            >
          </div>

          <p>
            StarExecCommand is a Java application that enables command-line
            interaction with the StarExec server. Automate job submission,
            benchmark uploads, and result retrieval from any system with Java
            installed.
          </p>

          <div class="feature-grid">
            <div class="feature-card">
              <div class="feature-header">
                <div class="feature-icon feature-icon--shell">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                    <path
                      d="M4 17l6-6-6-6M12 19h8"
                      stroke="white"
                      stroke-width="2"
                      stroke-linecap="round"
                    />
                  </svg>
                </div>
                <h3 class="feature-title">Interactive Shell</h3>
              </div>
              <p class="feature-description">
                Execute commands in real-time with immediate feedback
              </p>
            </div>

            <div class="feature-card">
              <div class="feature-header">
                <div class="feature-icon feature-icon--batch">
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                    <path
                      d="M9 12h6M9 16h6M17 21H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                      stroke="white"
                      stroke-width="2"
                    />
                  </svg>
                </div>
                <h3 class="feature-title">Batch Processing</h3>
              </div>
              <p class="feature-description">
                Run scripts with multiple commands for automation
              </p>
            </div>

          </div>

          <h3>Running StarExecCommand</h3>
          <pre><code>java -jar StarexecCommand.jar</code></pre>
          <p>
            After starting, you'll enter an interactive shell. Your first
            command should typically be <code>login</code>.
          </p>

          <div class="example-box">
            <div class="example-header">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path
                  d="M4 17l6-6-6-6M12 19h8"
                  stroke="currentColor"
                  stroke-width="2"
                />
              </svg>
              <span>Quick Example</span>
            </div>
            <pre class="example-code">
login u=your@email.com p=yourpassword
lssolvers id=5
getjobinfo id=472 out=results.zip
logout</pre
            >
          </div>
        </section>

        <!-- Command Syntax -->
        <section id="syntax" class="content-section" aria-labelledby="syntax-title">
          <h2 id="syntax-title" class="section-title">
            <svg
              class="section-icon"
              width="32"
              height="32"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path
                d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
              ></path>
              <polyline points="14 2 14 8 20 8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
              <polyline points="10 9 9 9 8 9"></polyline>
            </svg>
            Command Syntax
          </h2>

          <p>All commands follow a consistent pattern:</p>
          <pre><code>{command} {key=value} {key=value} ...</code></pre>

          <div class="syntax-rules">
            <h3>Syntax Rules</h3>
            <ul>
              <li>✓ No spaces between key and <code>=</code></li>
              <li>✓ Values may contain spaces</li>
              <li>✓ Commands and keys are <strong>case-insensitive</strong></li>
              <li>✓ Values are <strong>case-sensitive</strong></li>
              <li>✓ Parameters can be in any order</li>
            </ul>
          </div>

          <div class="example-box">
            <div class="example-header">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path
                  d="M9 12h6M9 16h6"
                  stroke="currentColor"
                  stroke-width="2"
                />
              </svg>
              <span>Example</span>
            </div>
            <pre class="example-code">
getjobinfo id=472 out=somefilelocation.zip</pre
            >
          </div>
        </section>

        <!-- Parameters Reference -->
        <section id="parameters" class="content-section" aria-labelledby="params-title">
          <h2 id="params-title" class="section-title">
            <svg
              class="section-icon"
              width="32"
              height="32"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="3"></circle>
              <path
                d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"
              ></path>
            </svg>
            Parameters Reference
          </h2>

          <p>Common parameters used across various commands:</p>

          <table class="param-table">
            <thead>
              <tr>
                <th>Parameter</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td><code>id</code></td>
                <td>ID of the primitive (space, job, benchmark, etc.)</td>
              </tr>
              <tr>
                <td><code>u</code></td>
                <td>
                  Username for login, or flag for listing user's own primitives
                </td>
              </tr>
              <tr>
                <td><code>p</code></td>
                <td>Password (not required if u=guest)</td>
              </tr>
              <tr>
                <td><code>out</code></td>
                <td>Output file path</td>
              </tr>
              <tr>
                <td><code>f</code></td>
                <td>Path to local file</td>
              </tr>
              <tr>
                <td><code>url</code></td>
                <td>URL of remote file</td>
              </tr>
              <tr>
                <td><code>n</code></td>
                <td>Name (defaults to current date if not specified)</td>
              </tr>
              <tr>
                <td><code>d</code></td>
                <td>Description text</td>
              </tr>
              <tr>
                <td><code>limit</code></td>
                <td>Maximum number of items to retrieve/print</td>
              </tr>
              <tr>
                <td><code>cpu</code></td>
                <td>CPU timeout in seconds for new jobs</td>
              </tr>
              <tr>
                <td><code>w</code></td>
                <td>Wallclock timeout in seconds</td>
              </tr>
              <tr>
                <td><code>mem</code></td>
                <td>Maximum memory in GB per job pair</td>
              </tr>
              <tr>
                <td><code>qid</code></td>
                <td>Queue ID</td>
              </tr>
              <tr>
                <td><code>pid</code></td>
                <td>Post processor ID</td>
              </tr>
              <tr>
                <td><code>preid</code></td>
                <td>Pre processor ID</td>
              </tr>
            </tbody>
          </table>

          <div class="tip-box">
            <div class="tip-icon">💡</div>
            <div class="tip-content">
              <strong>Pro Tip:</strong> Use <code>ow</code> (overwrite) flag to
              replace existing files when downloading.
            </div>
          </div>
        </section>

        <!-- Commands -->
        <section id="commands" class="content-section" aria-labelledby="commands-title">
          <h2 id="commands-title" class="section-title">
            <svg
              class="section-icon"
              width="32"
              height="32"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <polygon
                points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"
              ></polygon>
            </svg>
            Commands
          </h2>

          <!-- General Commands -->
          <h3 id="general-commands">General Commands</h3>

          <div class="command-grid">
            <div class="command">
              <h4>login</h4>
              <p>Authenticate with StarExec</p>
              <dl>
                <dt>Required</dt>
                <dd>
                  <code>u</code>, <code>p</code> (p not required if u=guest)
                </dd>
                <dt>Optional</dt>
                <dd><code>addr</code></dd>
              </dl>
              <pre><code>login u=user@example.com p=password</code></pre>
            </div>

            <div class="command">
              <h4>logout</h4>
              <p>End your StarExec session</p>
              <pre><code>logout</code></pre>
            </div>

            <div class="command">
              <h4>pausejob / resumejob</h4>
              <p>Pause or resume a running job</p>
              <dl>
                <dt>Required</dt>
                <dd><code>id</code> (job ID)</dd>
              </dl>
              <pre><code>pausejob id=123
resumejob id=123</code></pre>
            </div>
          </div>

          <!-- Download Commands -->
          <h3 id="download-commands">Download Commands</h3>
          <p>
            All downloads save as <code>.zip</code> files. Use
            <code>ow</code> flag to overwrite existing files.
          </p>

          <table class="command-table">
            <thead>
              <tr>
                <th>Command</th>
                <th>Description</th>
                <th>Required</th>
                <th>Optional</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td><code>getjobinfo</code></td>
                <td>Download job CSV</td>
                <td>id, out</td>
                <td>ow, incids, comp</td>
              </tr>
              <tr>
                <td><code>getjobout</code></td>
                <td>Download all job pair outputs</td>
                <td>id, out</td>
                <td>ow</td>
              </tr>
              <tr>
                <td><code>getjobpair</code></td>
                <td>Download single job pair output</td>
                <td>id, out</td>
                <td>ow, longpath</td>
              </tr>
              <tr>
                <td><code>getsolver</code></td>
                <td>Download a solver</td>
                <td>id, out</td>
                <td>ow</td>
              </tr>
              <tr>
                <td><code>getbench</code></td>
                <td>Download a benchmark</td>
                <td>id, out</td>
                <td>ow</td>
              </tr>
              <tr>
                <td><code>getspacexml</code></td>
                <td>Download space hierarchy as XML</td>
                <td>id, out</td>
                <td>ow, attr, pid</td>
              </tr>
            </tbody>
          </table>

          <!-- Upload Commands -->
          <h3 id="push-commands">Upload Commands</h3>

          <table class="command-table">
            <thead>
              <tr>
                <th>Command</th>
                <th>Description</th>
                <th>Required</th>
                <th>Optional</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td><code>pushsolver</code></td>
                <td>Upload a solver</td>
                <td>(f OR url), id</td>
                <td>n, d, df, downloadable, run, set, type</td>
              </tr>
              <tr>
                <td><code>pushbenchmarks</code></td>
                <td>Upload benchmarks</td>
                <td>(f OR url), id, bt</td>
                <td>d, df, dep, downloadable, hier, link, allPerm</td>
              </tr>
              <tr>
                <td><code>pushconfig</code></td>
                <td>Upload solver configuration</td>
                <td>f, id</td>
                <td>n, d</td>
              </tr>
              <tr>
                <td><code>pushspacexml</code></td>
                <td>Upload space XML</td>
                <td>f, id</td>
                <td>-</td>
              </tr>
              <tr>
                <td><code>pushjobxml</code></td>
                <td>Upload job XML</td>
                <td>f, id</td>
                <td>-</td>
              </tr>
            </tbody>
          </table>

          <!-- List Commands -->
          <h3 id="list-commands">List Commands</h3>
          <p>
            Print contents of a space. Use <code>u</code> instead of
            <code>id</code> to list your own primitives.
          </p>

          <table class="command-table">
            <thead>
              <tr>
                <th>Command</th>
                <th>Description</th>
                <th>Required</th>
                <th>Optional</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td><code>lsbenchmarks</code></td>
                <td>List benchmarks</td>
                <td>id OR u</td>
                <td>limit</td>
              </tr>
              <tr>
                <td><code>lsjobs</code></td>
                <td>List jobs</td>
                <td>id OR u</td>
                <td>limit</td>
              </tr>
              <tr>
                <td><code>lssolvers</code></td>
                <td>List solvers</td>
                <td>id OR u</td>
                <td>limit</td>
              </tr>
              <tr>
                <td><code>lsconfigs</code></td>
                <td>List solver configurations</td>
                <td>id (solver)</td>
                <td>limit</td>
              </tr>
              <tr>
                <td><code>lssubspaces</code></td>
                <td>List subspaces</td>
                <td>id</td>
                <td>limit</td>
              </tr>
              <tr>
                <td><code>lsusers</code></td>
                <td>List users in space</td>
                <td>id</td>
                <td>limit</td>
              </tr>
            </tbody>
          </table>

          <div class="example-box">
            <div class="example-header">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path
                  d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 02 2h10a2 2 0 0 02-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 02 2h2a2 2 0 0 02-2M9 5a2 2 0 0 12-2h2a2 2 0 0 12 2"
                  stroke="currentColor"
                  stroke-width="2"
                />
              </svg>
              <span>Example: Listing Resources</span>
            </div>
            <pre class="example-code">
lsjobs id=4 limit=100
lssolvers u=</pre
            >
          </div>
        </section>

        <!-- Modes -->
        <section id="modes" class="content-section" aria-labelledby="modes-title">
          <h2 id="modes-title" class="section-title">
            <svg
              class="section-icon"
              width="32"
              height="32"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M23 4v6h-6"></path>
              <path d="M1 20v-6h6"></path>
              <path
                d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"
              ></path>
            </svg>
            Shell & Batch Modes
          </h2>

          <div class="mode-comparison">
            <div class="mode-card">
              <h3>Shell Mode (Default)</h3>
              <p>
                Commands are entered interactively at the prompt with immediate
                execution.
              </p>
              <div class="mode-features">
                <span class="feature-tag">Interactive</span>
                <span class="feature-tag">Real-time feedback</span>
              </div>
            </div>

            <div class="mode-card">
              <h3>Batch Mode</h3>
              <p>Execute commands from a file. Place one command per line.</p>
              <pre><code>runfile f=commands.txt verbose=</code></pre>
              <div class="mode-features">
                <span class="feature-tag">Automation</span>
                <span class="feature-tag">Scripting</span>
              </div>
            </div>
          </div>
        </section>

        <!-- Scripting -->
        <section id="scripting" class="content-section" aria-labelledby="scripting-title">
          <h2 id="scripting-title" class="section-title">
            <svg
              class="section-icon"
              width="32"
              height="32"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path
                d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"
              ></path>
              <polyline points="14 2 14 8 20 8"></polyline>
            </svg>
            Scripting with Variables
          </h2>

          <p>
            StarExecCommand supports variables for basic scripting. Variable
            names start with <code>$</code> and can contain letters, numbers,
            and underscores. Names are case-sensitive.
          </p>

          <h3>Setting Variables</h3>
          <p>Capture the ID returned from a create command:</p>
          <pre><code>$NewSpace=createsubspace id=5</code></pre>

          <h3>Using Variables</h3>
          <p>Reference variables as parameter values:</p>
          <pre><code>linkbench id=6 from=63 to=$NewSpace</code></pre>

          <div class="example-box">
            <div class="example-header">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path
                  d="M9 12h6M9 16h6M17 21H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  stroke="currentColor"
                  stroke-width="2"
                />
              </svg>
              <span>Complete Workflow Example</span>
            </div>
            <pre class="example-code">
login u=user@example.com p=password
$NewSpace=createsubspace id=5 n="My New Space"
pushbenchmarks f=benchmarks.zip id=$NewSpace bt=1
pushsolver f=solver.tar id=$NewSpace
createjob id=$NewSpace qid=1 cpu=300 w=600
logout</pre
            >
          </div>
        </section>
      </article>
      </div>
    </div>
  </main>
</star:template>
