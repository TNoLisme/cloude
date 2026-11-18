import type { ReactNode } from "react";
import { Brand, Simulation } from "../../../components/shared";
import styles from "./AuthSplitLayout.module.css";

export function AuthSplitLayout({ children }: { children: ReactNode }) {
  return (
    <main className={styles.layout}>
      <div className={styles.artwork} aria-hidden="true">
        <div className={styles.artworkName}>DIGITAL BANKING</div>
      </div>
      <section className={styles.panel} aria-label="Đăng nhập Digital Banking">
        <div className={styles.content}>
          <Brand compact />
          {children}
        </div>
        <footer className={styles.footer}>
          <Simulation />
        </footer>
      </section>
    </main>
  );
}
