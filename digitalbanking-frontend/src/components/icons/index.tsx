import type { CSSProperties } from "react";
import { featureIcons, illustrations, navigationIcons } from "./catalog";
import styles from "./Icons.module.css";

type Size = "sm" | "md" | "lg";
type State = "inactive" | "active" | "disabled";
const navigationSizes: Record<Size, number> = { sm: 16, md: 20, lg: 24 };
const featureSizes: Record<Size, number> = { sm: 32, md: 48, lg: 64 };

export function Icon({
  name,
  size = "md",
  state,
  className,
}: {
  name: keyof typeof navigationIcons;
  size?: Size;
  state?: State;
  className?: string;
}) {
  return (
    <span
      aria-hidden="true"
      className={`${styles.icon} ${state ? styles[state] : ""} ${className ?? ""}`}
      style={
        {
          "--icon-size": `${navigationSizes[size]}px`,
          "--icon-url": `url("${navigationIcons[name]}")`,
        } as CSSProperties
      }
    />
  );
}

export function FeatureIcon({
  name,
  size = "md",
  className,
}: {
  name: keyof typeof featureIcons;
  size?: Size;
  className?: string;
}) {
  return (
    <img
      alt=""
      aria-hidden="true"
      className={`${styles.feature} ${className ?? ""}`}
      src={featureIcons[name]}
      width={featureSizes[size]}
      height={featureSizes[size]}
      style={{ "--icon-size": `${featureSizes[size]}px` } as CSSProperties}
    />
  );
}

export function Illustration({
  name,
  className,
}: {
  name: keyof typeof illustrations;
  className?: string;
}) {
  return (
    <img
      alt=""
      aria-hidden="true"
      className={`${styles.illustration} ${className ?? ""}`}
      src={illustrations[name]}
      width={240}
      height={160}
    />
  );
}
