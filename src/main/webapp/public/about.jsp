<%@page contentType="text/html" pageEncoding="UTF-8" %> <%@page
trimDirectiveWhitespaces="true" %> <%@taglib prefix="star"
tagdir="/WEB-INF/tags" %>

<star:template title="About StarExec" css="public/about">
  <main role="main" class="about-page">
    <!-- Hero Section -->
    <section class="about-hero">
      <div class="container">
        <h1 class="hero-title">About StarExec</h1>
        <p class="hero-description">
          A cross community logic solving service developed at the University of
          Iowa
        </p>
      </div>
    </section>

    <!-- Content Sections -->
    <div class="container">
      <!-- Mission Section -->
      <section class="content-section" aria-labelledby="mission-heading">
        <h2 id="mission-heading" class="section-title">
          <svg
            class="section-icon"
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
          >
            <circle cx="12" cy="12" r="10"></circle>
            <path d="M12 6v6l4 2"></path>
          </svg>
          Our Mission
        </h2>

        <div class="text-content">
          <p>
            StarExec is a cross community logic solving service developed at the
            University of Iowa under the direction of principal investigators
            Aaron Stump (Iowa), Geoff Sutcliffe (University of Miami), and
            Cesare Tinelli (Iowa).
          </p>

          <p>
            Its main goal is to facilitate the experimental evaluation of logic
            solvers, broadly understood as automated tools based on formal
            reasoning. The service is designed to provide a single piece of
            storage and computing infrastructure to logic solving communities
            and their members. It aims at reducing duplication of effort and
            resources as well as enabling individual researchers or groups with
            no access to comparable infrastructure.
          </p>
        </div>

        <!-- Features Grid -->
        <div class="features-grid">
          <div class="feature-card">
            <div class="feature-icon feature-icon--benchmarks">
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
              >
                <path d="M3 3h18v18H3z"></path>
                <path d="M12 8v8m-4-4h8"></path>
              </svg>
            </div>
            <h3 class="feature-title">Benchmark Libraries</h3>
            <p class="feature-description">
              Community organizers can store, manage and make available
              benchmark libraries
            </p>
          </div>

          <div class="feature-card">
            <div class="feature-icon feature-icon--competitions">
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
              >
                <path
                  d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6M18 9h1.5a2.5 2.5 0 0 0 0-5H18"
                ></path>
                <path
                  d="M4 22h16M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22M18 2H6v7a6 6 0 0 0 12 0V2Z"
                ></path>
              </svg>
            </div>
            <h3 class="feature-title">Competitions</h3>
            <p class="feature-description">
              Competition organizers can run logic solver competitions with
              standardized infrastructure
            </p>
          </div>

          <div class="feature-card">
            <div class="feature-icon feature-icon--evaluation">
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
              >
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
              </svg>
            </div>
            <h3 class="feature-title">Comparative Evaluation</h3>
            <p class="feature-description">
              Community members can perform comparative evaluations of logic
              solvers on public or private benchmarks
            </p>
          </div>
        </div>
      </section>

      <!-- Resources Section -->
      <section class="content-section" aria-labelledby="resources-heading">
        <h2 id="resources-heading" class="section-title">
          <svg
            class="section-icon"
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
          >
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path
              d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"
            ></path>
          </svg>
          Resources
        </h2>

        <div class="resources-grid">
          <a href="machine-specs.txt" class="resource-link" rel="external">
            <svg
              class="resource-icon"
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
            >
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="3" y1="9" x2="21" y2="9"></line>
              <line x1="9" y1="21" x2="9" y2="9"></line>
            </svg>
            <span class="resource-text">Machine Specifications</span>
            <span class="external-icon">→</span>
          </a>

          <a
            href="https://www.starexec.org/vmimage/"
            class="resource-link"
            target="_blank"
            rel="noopener noreferrer external"
          >
            <svg
              class="resource-icon"
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
            >
              <circle cx="12" cy="12" r="10"></circle>
              <circle cx="12" cy="12" r="3"></circle>
              <line x1="12" y1="2" x2="12" y2="9"></line>
              <line x1="12" y1="15" x2="12" y2="22"></line>
            </svg>
            <span class="resource-text">Virtual Machine Image</span>
            <span class="external-icon">↗</span>
          </a>

          <a
            href="STAREXEC TERMS OF SERVICE.doc"
            class="resource-link"
            rel="help"
          >
            <svg
              class="resource-icon"
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
            >
              <path
                d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
              ></path>
              <polyline points="14 2 14 8 20 8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
              <polyline points="10 9 9 9 8 9"></polyline>
            </svg>
            <span class="resource-text">Terms of Service</span>
            <span class="external-icon">→</span>
          </a>

          <a
            href="http://wiki.uiowa.edu/display/stardev/Home"
            class="resource-link"
            target="_blank"
            rel="noopener noreferrer external"
          >
            <svg
              class="resource-icon"
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
            >
              <path
                d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"
              ></path>
            </svg>
            <span class="resource-text">Development Wiki</span>
            <span class="external-icon">↗</span>
          </a>
        </div>
      </section>

      <!-- Advisory Committee -->
      <section class="content-section" aria-labelledby="advisory-heading">
        <h2 id="advisory-heading" class="section-title">Advisory Committee</h2>
        <div class="committee-grid">
          <div class="committee-member">
            Nikolaj Bjørner
            <span class="affiliation">(Microsoft Research)</span>
          </div>
          <div class="committee-member">
            Ewen Denney <span class="affiliation">(NASA Ames)</span>
          </div>
          <div class="committee-member">
            Aarti Gupta <span class="affiliation">(NEC Labs)</span>
          </div>
          <div class="committee-member">
            Ian Horrocks <span class="affiliation">(Oxford)</span>
          </div>
          <div class="committee-member">
            Giovambattista Ianni
            <span class="affiliation">(University of Calabria)</span>
          </div>
          <div class="committee-member">
            Daniel Le Berre
            <span class="affiliation">(University of Artois)</span>
          </div>
          <div class="committee-member">
            Johannes Waldmann
            <span class="affiliation"
              >(Leipzig University of Applied Sciences)</span
            >
          </div>
        </div>
      </section>

      <!-- Credits Section -->
      <section class="content-section" aria-labelledby="credits-heading">
        <h2 id="credits-heading" class="section-title">Credits</h2>

        <div class="credits-content">
          <h3 class="subsection-title">Funding</h3>
          <div class="text-content">
            <p>
              StarExec was first supported by a US$2.11 million grant from the
              National Science Foundation. A further grant of US$1.00 million
              provided support for further development.
            </p>
          </div>

          <div class="funding-links">
            <a
              href="http://www.fastlane.nsf.gov/servlet/showaward?award=1058748"
              class="funding-card"
              target="_blank"
              rel="noopener noreferrer external"
            >
              <span class="funding-label">NSF Award #1058748</span>
              <span class="funding-institution">University of Iowa</span>
            </a>

            <a
              href="http://www.fastlane.nsf.gov/servlet/showaward?award=1058925"
              class="funding-card"
              target="_blank"
              rel="noopener noreferrer external"
            >
              <span class="funding-label">NSF Award #1058925</span>
              <span class="funding-institution">University of Miami</span>
            </a>

            <a
              href="http://www.fastlane.nsf.gov/servlet/showaward?award=1729603"
              class="funding-card"
              target="_blank"
              rel="noopener noreferrer external"
            >
              <span class="funding-label">NSF Award #1729603</span>
              <span class="funding-institution">University of Iowa</span>
            </a>

            <a
              href="http://www.fastlane.nsf.gov/servlet/showaward?award=1730419"
              class="funding-card"
              target="_blank"
              rel="noopener noreferrer external"
            >
              <span class="funding-label">NSF Award #1730419</span>
              <span class="funding-institution">University of Miami</span>
            </a>
          </div>

          <h3 class="subsection-title">Development Team</h3>
          <div class="team-list">
            <span class="team-member">Eric Burns</span> (Iowa),
            <span class="team-member">Todd Elvers</span> (Iowa),
            <span class="team-member">Albert Giegerich</span> (Iowa),
            <span class="team-member">Pat Hawks</span> (Iowa),
            <span class="team-member">Tyler Jensen</span> (Iowa),
            <span class="team-member">Wyatt Kaiser</span> (Iowa),
            <span class="team-member">Ben McCune</span> (Iowa),
            <span class="team-member">CJ Palmer</span> (Iowa),
            <span class="team-member">Vivek Sardeshmukh</span> (Iowa),
            <span class="team-member">Skylar Stark</span> (Iowa),
            <span class="team-member">Ruoyu Zhang</span> (Iowa),
            <span class="team-member">Rahul Dass</span> (Miami),
            <span class="team-member">Pedro Davila</span> (Miami),
            <span class="team-member">John McKeown</span> (Miami),
            <span class="team-member">Joseph Masterjohn</span> (Miami), and
            <span class="team-member">Muhammad Nassar</span> (Miami).
          </div>

          <h3 class="subsection-title">System Support</h3>
          <div class="team-list">
            Computer system support and assistance in designing and building the
            hardware infrastructure was provided by
            <span class="team-member">Hugh Brown</span> (Iowa),
            <span class="team-member">Dan Holstad</span> (Iowa),
            <span class="team-member">Jamie Tisdale</span> (Iowa),
            <span class="team-member">JJ Ulrich</span> (Iowa), and
            <span class="team-member">Joel Zysman</span> (Miami).
          </div>

          <h3 class="subsection-title">Contributors</h3>
          <div class="team-list">
            In addition to the members of the Advisory Board, the following
            people have provided useful feedback and input: Clark Barrett,
            Christoph Benzmüller, Armin Biere, David Cok, Morgan Deters, Jürgen
            Giesl, Alberto Griggio, Thomas Krennwallner, Jens Otten, Andrei
            Paskevich, Olivier Roussel, Martina Seidl, Stephan Schulz, Michael
            Tautschnig, Christoph Wintersteiger, and Harald Zankl.
          </div>
        </div>
      </section>
    </div>
  </main>
</star:template>
